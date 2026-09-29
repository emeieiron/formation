package xyz.mcxross.formation.challenge.circuit

import kotlin.math.abs
import kotlin.random.Random
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeGame
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.GameStatus
import xyz.mcxross.formation.session.Stat

enum class Action(val verb: String, val hint: String) {
  TAP("Tap", "Tap the node"),
  HOLD("Hold", "Press and hold until the ring fills"),
  SHAKE("Shake", "Shake your phone"),
  TURN("Turn", "Turn your phone on its side"),
  FLIP("Flip", "Lay your phone face down"),
  MATCH("Match", "Pick the symbol your neighbour calls out"),
  SYNC("Sync", "Tap at the same moment as your partner"),
}

@Serializable
data class Pulse(
  val id: Int,
  val node: Int,
  val action: Action,
  val arriveAt: Long,
  val deadline: Long,
  val partner: Int? = null,
  val symbol: Int? = null,
  val options: List<Int> = emptyList(),
  val caller: Int? = null,
  val synced: List<Int> = emptyList(),
)

@Serializable
sealed interface CircuitEvent {
  val pulse: Int
  val node: Int

  @Serializable
  @SerialName("pass")
  data class Passed(override val pulse: Int, override val node: Int) : CircuitEvent

  @Serializable
  @SerialName("break")
  data class Broke(override val pulse: Int, override val node: Int, val reason: String) :
    CircuitEvent
}

@Serializable
data class CircuitState(
  val nodes: List<PlayerId>,
  val loops: Int,
  val loop: Int = 0,
  val lives: Int,
  val maxLives: Int,
  val passed: Int = 0,
  val total: Int,
  val pulse: Pulse? = null,
  val event: CircuitEvent? = null,
)

@Serializable
data class CircuitInput(val pulse: Int, val action: Action, val at: Long, val choice: Int? = null)

internal class CircuitGame(private val setup: ChallengeSetup) :
  ChallengeGame<CircuitState, CircuitInput> {
  private val random = Random(setup.seed)
  private val tuning = Tuning.of(setup.difficulty)
  private val n = setup.players.size
  private val syncTimes = mutableMapOf<Int, Long>()
  private var nextId = 1
  private var resume: Pair<Int, Long>? = null
  private var lastAction: Action? = null

  override var state: CircuitState
    private set

  override var status: GameStatus = GameStatus.Running
    private set

  init {
    val loops = loops(n, setup.difficulty)
    state =
      CircuitState(
        setup.players,
        loops,
        lives = tuning.lives,
        maxLives = tuning.lives,
        total = loops * n,
      )
    send(node = 0, at = setup.startAt)
  }

  override fun input(from: PlayerId, input: CircuitInput, now: Long) {
    val p = state.pulse ?: return
    if (status != GameStatus.Running || input.pulse != p.id || input.action != p.action) return
    val node = state.nodes.indexOf(from)
    if (node != p.node && node != p.partner) return
    if (now < p.arriveAt - EARLY_MS) return
    when (p.action) {
      Action.MATCH ->
        if (input.choice == p.symbol) pass(p, now)
        else fail(p, node, "picked the wrong symbol", now)
      Action.SYNC -> {
        if (node in syncTimes) return
        syncTimes[node] = input.at
        if (syncTimes.size < 2) {
          state = state.copy(pulse = p.copy(synced = syncTimes.keys.toList()))
          return
        }
        val (a, b) = syncTimes.entries.sortedBy { it.value }
        if (abs(b.value - a.value) <= SYNC_WINDOW_MS) pass(p, now)
        else fail(p, b.key, "tapped out of sync", now)
      }
      else -> pass(p, now)
    }
  }

  override fun tick(now: Long) {
    if (status != GameStatus.Running) return
    resume?.let { (node, at) ->
      if (now >= at) {
        resume = null
        send(node, now)
      }
      return
    }
    val p = state.pulse ?: return
    if (now <= p.deadline + GRACE_MS) return
    val late =
      if (p.action == Action.SYNC)
        listOfNotNull(p.node, p.partner).firstOrNull { it !in syncTimes } ?: p.node
      else p.node
    fail(p, late, "ran out of time", now)
  }

  private fun pass(p: Pulse, now: Long) {
    val span = if (p.action == Action.SYNC) 2 else 1
    var next = p.node + span
    var loop = state.loop
    if (next >= n) {
      next -= n
      loop += 1
    }
    state =
      state.copy(
        passed = state.passed + span,
        loop = loop,
        pulse = null,
        event = CircuitEvent.Passed(p.id, p.node),
      )
    if (loop >= state.loops) {
      status =
        GameStatus.Won(
          "${state.loops} ${if (state.loops == 1) "loop" else "loops"} of the circuit",
          stats(),
        )
      return
    }
    send(next, now)
  }

  private fun fail(p: Pulse, node: Int, reason: String, now: Long) {
    val lives = state.lives - 1
    state = state.copy(lives = lives, pulse = null, event = CircuitEvent.Broke(p.id, node, reason))
    if (lives <= 0) {
      status = GameStatus.Lost(reason, state.nodes[node], stats())
      return
    }
    resume = p.node to now + RECOVER_MS
  }

  private fun send(node: Int, at: Long) {
    syncTimes.clear()
    val action = pick(node)
    lastAction = action
    val arrive = at + TRAVEL_MS
    val limit = (tuning.limit(action) * speedup(state.loop)).toLong()
    val id = nextId++
    val pulse =
      when (action) {
        Action.SYNC -> Pulse(id, node, action, arrive, arrive + limit, partner = node + 1)
        Action.MATCH -> {
          val symbol = random.nextInt(SYMBOLS)
          val options =
            (listOf(symbol) + (0 until SYMBOLS).filter { it != symbol }.shuffled(random).take(3))
              .shuffled(random)
          Pulse(
            id,
            node,
            action,
            arrive,
            arrive + limit,
            symbol = symbol,
            options = options,
            caller = (node - 1 + n) % n,
          )
        }
        else -> Pulse(id, node, action, arrive, arrive + limit)
      }
    state = state.copy(pulse = pulse)
  }

  private fun pick(node: Int): Action {
    val options = buildList {
      repeat(3) { add(Action.TAP) }
      repeat(2) { add(Action.HOLD) }
      repeat(2) { add(Action.SHAKE) }
      repeat(2) { add(Action.TURN) }
      add(Action.FLIP)
      if (n >= 2) repeat(2) { add(Action.MATCH) }
      // Both partners must be in this loop, so the pulse never skips the finish.
      if (node + 1 < n) repeat(2) { add(Action.SYNC) }
    }
    val pool = options.filter { it != lastAction }.ifEmpty { options }
    return pool[random.nextInt(pool.size)]
  }

  private fun speedup(loop: Int) = listOf(1f, 0.86f, 0.76f, 0.68f, 0.62f)[loop.coerceIn(0, 4)]

  private fun stats() =
    listOf(
      Stat("Nodes passed", "${state.passed} of ${state.total}"),
      Stat("Lives left", state.lives.toString()),
    )

  private class Tuning(val lives: Int, val scale: Float) {
    fun limit(action: Action): Float =
      scale *
        when (action) {
          Action.TAP -> 2_400f
          Action.HOLD -> 3_200f
          Action.SHAKE -> 3_400f
          Action.TURN -> 3_200f
          Action.FLIP -> 3_600f
          Action.MATCH -> 4_600f
          Action.SYNC -> 3_000f
        }

    companion object {
      fun of(d: Difficulty) =
        when (d) {
          Difficulty.EASY -> Tuning(4, 1.3f)
          Difficulty.NORMAL -> Tuning(3, 1f)
          Difficulty.HARD -> Tuning(3, 0.85f)
          Difficulty.EXTREME -> Tuning(2, 0.72f)
        }
    }
  }

  companion object {
    const val TRAVEL_MS = 450L
    const val SYNC_WINDOW_MS = 350L
    const val GRACE_MS = 150L
    const val RECOVER_MS = 1_800L
    const val EARLY_MS = 150L
    const val SYMBOLS = 6

    // Enough loops for a dozen or so moves, whatever the group size.
    fun loops(players: Int, difficulty: Difficulty): Int {
      val moves =
        when (difficulty) {
          Difficulty.EASY -> 8
          Difficulty.NORMAL -> 12
          Difficulty.HARD -> 15
          Difficulty.EXTREME -> 18
        }
      return ((moves + players - 1) / players).coerceIn(1, 4)
    }
  }
}
