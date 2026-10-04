package xyz.mcxross.formation.ricochet

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeGame
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.GameStatus
import xyz.mcxross.formation.session.Stat

internal class RicochetGame(setup: ChallengeSetup) : ChallengeGame<RicochetState, MovePaddle> {
  private val random = Random(setup.seed)
  private val pacing = Pacing(setup.difficulty)
  private val firstSide = random.nextInt(2)
  private var eventId = 0L

  init {
    require(setup.players.size == 2 && setup.players.distinct().size == 2) { "Ricochet needs two distinct players" }
  }

  override var state = RicochetState(
    paddles = setup.players.mapIndexed { side, player -> Paddle(player, side, Arena.HEIGHT / 2) },
    pulse = serve(1), targets = Arena.targets(random), paddleHeight = pacing.paddleHeight,
    at = setup.startAt, startAt = setup.startAt, endsAt = setup.startAt + RicochetState.LIMIT_MS,
    serveAt = setup.startAt + RESET_MS,
  )
    private set

  override var status: GameStatus = GameStatus.Running
    private set

  override fun input(from: PlayerId, input: MovePaddle, now: Long) {
    if (state.paddles.none { it.player == from } || !input.y.isFinite() || now < state.at) return
    advance(now)
    if (status != GameStatus.Running || input.rally != state.rally || now < state.startAt) return
    val paddle = state.paddle(from)
    if (input.sequence <= paddle.sequence) return
    state = state.copy(paddles = state.paddles.map {
      if (it.player == from) it.copy(y = Arena.paddleY(input.y, state.paddleHeight), sequence = input.sequence) else it
    })
  }

  override fun tick(now: Long) = advance(now)

  private fun advance(now: Long) {
    val until = minOf(now, state.endsAt)
    while (status == GameStatus.Running && state.at + STEP_MS <= until) {
      val at = state.at + STEP_MS
      state = state.copy(at = at)
      if (at <= state.serveAt) continue
      val flight = Physics.step(state.pulse, state.targets, state.paddles, state.paddleHeight, STEP_MS / 1_000.0)
      val impacts = flight.contacts.map { Impact(++eventId, it.kind, at, it.x, it.y, it.side, it.target) }
      state = state.copy(pulse = flight.pulse, targets = flight.targets,
        returns = state.returns + flight.contacts.count { it.kind == ImpactKind.Paddle },
        impacts = (state.impacts + impacts).takeLast(12))
      if (state.targets.isEmpty()) {
        state = state.copy(finishedAt = at)
        status = GameStatus.Won("Ricochet complete", stats())
      } else if (flight.missed) {
        val misses = state.misses + 1
        state = state.copy(misses = misses)
        if (misses == RicochetState.MAX_MISSES) {
          state = state.copy(finishedAt = at)
          status = GameStatus.Lost("The formation missed three returns.", stats = stats())
        } else {
          val rally = state.rally + 1
          state = state.copy(rally = rally, serveAt = at + RESET_MS, pulse = serve(rally),
            paddles = state.paddles.map { it.copy(sequence = 0) })
        }
      }
    }
    if (status == GameStatus.Running && now >= state.endsAt) {
      state = state.copy(finishedAt = state.endsAt)
      status = GameStatus.Lost("The formation ran out of time.", stats = stats())
    }
  }

  private fun serve(rally: Int): Pulse {
    val direction = if ((firstSide + rally).mod(2) == 0) 1.0 else -1.0
    val angle = random.nextDouble(-0.45, 0.45)
    return Pulse(1.0, Arena.HEIGHT / 2, cos(angle) * pacing.speed * direction, sin(angle) * pacing.speed)
  }

  private fun stats() = listOf(
    Stat("Targets", "${state.clears}/${RicochetState.TARGETS}"),
    Stat("Returns", "${state.returns}"),
    Stat("Misses", "${state.misses}"),
  )

  companion object {
    const val STEP_MS = 10L
    const val RESET_MS = 1_200L
  }
}
