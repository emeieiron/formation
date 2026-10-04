package xyz.mcxross.formation.mosaic

import kotlin.math.abs
import kotlin.random.Random
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeGame
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.GameStatus
import xyz.mcxross.formation.session.Stat

internal class Pacing(difficulty: Difficulty) {
  private val perSeamMs = when (difficulty) {
    Difficulty.EASY -> 9_000L
    Difficulty.NORMAL -> 7_000L
    Difficulty.HARD -> 5_000L
    Difficulty.EXTREME -> 4_000L
  }
  val toleranceMm = when (difficulty) {
    Difficulty.EASY, Difficulty.NORMAL -> 8.0
    Difficulty.HARD -> 6.0
    Difficulty.EXTREME -> 5.0
  }
  val labels = difficulty == Difficulty.EASY
  val unsealedMarks = difficulty == Difficulty.EASY || difficulty == Difficulty.NORMAL

  fun limitMs(seams: Int) = BASE_MS + perSeamMs * seams

  private companion object {
    const val BASE_MS = 20_000L
  }
}

internal class MosaicGame(setup: ChallengeSetup) : ChallengeGame<MosaicState, Pinch> {
  private class Half(val player: PlayerId, val edge: Edge, val alongMm: Double, val upAt: Long)

  private val pending = mutableListOf<Half>()
  private val lastPinch = mutableMapOf<PlayerId, Long>()
  private var eventId = 0L

  override var state: MosaicState
    private set

  override var status: GameStatus = GameStatus.Running
    private set

  init {
    require(setup.players.size in Layouts.groupSizes && setup.players.distinct().size == setup.players.size) {
      "Mosaic needs 6, 9 or 18 distinct phones"
    }
    require(setup.players.all { it in setup.screens }) { "Mosaic needs every phone's screen" }
    val grid = Layouts.grid(setup.players.size)
    val fragment = Layouts.fragment(setup.players.map { setup.screens.getValue(it) }, grid.placement)
    val pacing = Pacing(setup.difficulty)
    state = MosaicState(
      grid = grid, fragment = fragment, gaps = Layouts.gaps(grid.placement),
      positions = Deal.deal(setup.players, setup.screens, grid, fragment, Random(setup.seed)),
      toleranceMm = pacing.toleranceMm, labels = pacing.labels, unsealedMarks = pacing.unsealedMarks,
      startAt = setup.startAt, endsAt = setup.startAt + pacing.limitMs(grid.seams().size),
    )
  }

  override fun input(from: PlayerId, input: Pinch, now: Long) {
    if (from !in state.positions || status != GameStatus.Running) return
    advance(now)
    if (status != GameStatus.Running || now < state.startAt) return
    if (input.id <= (lastPinch[from] ?: Long.MIN_VALUE) || !input.alongMm.isFinite() || abs(input.alongMm) > MAX_ALONG_MM) return
    if (input.upAt < input.downAt || input.upAt - input.downAt > MAX_DRAG_MS) return
    lastPinch[from] = input.id
    // Trust the phone's release time only within the late window, so a stamp can't reach back to pair.
    val upAt = input.upAt.coerceIn(now - LATE_MS, now)
    if (upAt < state.startAt || state.grid.neighbour(state.position(from), input.edge) == null) return
    pending += Half(from, input.edge, input.alongMm, upAt)
    resolve(now)
  }

  override fun tick(now: Long) = advance(now)

  private fun advance(now: Long) {
    if (status != GameStatus.Running) return
    resolve(now)
    if (status == GameStatus.Running && now >= state.endsAt) {
      state = state.copy(finishedAt = state.endsAt)
      status = GameStatus.Lost("The formation ran out of time.", stats = stats())
    }
  }

  private fun resolve(now: Long) {
    // A half that meets its real neighbour seals or asks for alignment at once.
    while (status == GameStatus.Running) {
      val pair = pending.sortedBy { it.upAt }.firstNotNullOfOrNull { half ->
        pending.filter { facing(half, it) && neighbours(half, it) }.minByOrNull { abs(it.upAt - half.upAt) }?.let { half to it }
      } ?: break
      pending -= pair.first
      pending -= pair.second
      seam(pair.first, pair.second, now)
    }
    // A half with no neighbouring partner after the settle window pairs with whoever faced it: a wrong pair.
    while (status == GameStatus.Running) {
      val pair = pending.filter { now - it.upAt >= SETTLE_MS }.sortedBy { it.upAt }.firstNotNullOfOrNull { half ->
        pending.filter { facing(half, it) }.minByOrNull { abs(it.upAt - half.upAt) }?.let { half to it }
      } ?: break
      pending -= pair.first
      pending -= pair.second
      wrong(pair.first, pair.second, now)
    }
    pending.removeAll { now - it.upAt > EXPIRE_MS }
  }

  private fun facing(half: Half, other: Half) =
    other !== half && other.player != half.player && other.edge == half.edge.opposite && abs(other.upAt - half.upAt) <= PAIR_MS

  private fun neighbours(half: Half, other: Half) =
    state.grid.neighbour(state.position(half.player), half.edge) == state.position(other.player)

  private fun seam(half: Half, other: Half, now: Long) {
    val positions = listOf(state.position(half.player), state.position(other.player)).sorted()
    val seam = state.seams.first { it.first == positions[0] && it.second == positions[1] }
    val players = listOf(half.player, other.player)
    if (abs(half.alongMm - other.alongMm) > state.toleranceMm) {
      record(SeamOutcome.Misaligned, players, seam.id, now)
      return
    }
    if (seam.id in state.sealed) return
    state = state.copy(sealed = state.sealed + seam.id)
    record(SeamOutcome.Sealed, players, seam.id, now)
    if (state.won) {
      state = state.copy(finishedAt = now)
      status = GameStatus.Won("The mark is whole", stats())
    }
  }

  private fun wrong(half: Half, other: Half, now: Long) {
    val misses = state.misses + 1
    state = state.copy(misses = misses)
    record(SeamOutcome.Wrong, listOf(half.player, other.player), null, now)
    if (misses >= MosaicState.MAX_MISSES) {
      state = state.copy(finishedAt = now)
      status = GameStatus.Lost("The formation joined three wrong pairs.", stats = stats())
    }
  }

  private fun record(outcome: SeamOutcome, players: List<PlayerId>, seam: Int?, now: Long) {
    state = state.copy(events = (state.events + SeamEvent(++eventId, outcome, now, players, seam)).takeLast(MAX_EVENTS))
  }

  private fun stats() = listOf(
    Stat("Seams", "${state.sealed.size}/${state.seams.size}"),
    Stat("Wrong pairs", "${state.misses}"),
  )

  companion object {
    // Both fingers of one pinch lift within this window.
    const val PAIR_MS = 200L
    // A half waits this long for its neighbour before it can count as a wrong pair.
    const val SETTLE_MS = 350L
    const val EXPIRE_MS = 700L
    const val LATE_MS = 300L
    const val MAX_DRAG_MS = 3_000L
    const val MAX_ALONG_MM = 1_000.0
    const val MAX_EVENTS = 12
  }
}
