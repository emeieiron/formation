package xyz.mcxross.formation.challenge.rally

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

@Serializable
data class Ball(
  val id: Int,
  val from: PlayerId?,
  val to: PlayerId,
  val launchAt: Long,
  val arriveAt: Long,
  val window: Int,
)

@Serializable
sealed interface RallyEvent {
  val ball: Int
  val player: PlayerId

  @Serializable
  @SerialName("hit")
  data class Hit(override val ball: Int, override val player: PlayerId, val errorMs: Int) :
    RallyEvent

  // true: swung before the window, false: after it, null: never swung.
  @Serializable
  @SerialName("miss")
  data class Miss(override val ball: Int, override val player: PlayerId, val early: Boolean?) :
    RallyEvent
}

@Serializable
data class RallyState(
  val players: List<PlayerId>,
  val target: Int,
  val streak: Int = 0,
  val best: Int = 0,
  val lives: Int,
  val maxLives: Int,
  val ball: Ball? = null,
  val event: RallyEvent? = null,
)

@Serializable data class RallyInput(val ball: Int, val at: Long)

internal class RallyGame(private val setup: ChallengeSetup) :
  ChallengeGame<RallyState, RallyInput> {
  private val random = Random(setup.seed)
  private val tuning = Tuning.of(setup.difficulty)
  private val laps = mutableListOf<PlayerId>()
  private var judged = false

  override var state =
    RallyState(
      players = setup.players,
      target = target(setup.players.size, setup.difficulty),
      lives = tuning.lives,
      maxLives = tuning.lives,
    )
    private set

  override var status: GameStatus = GameStatus.Running
    private set

  init {
    serve(setup.startAt + SERVE_DELAY_MS)
  }

  override fun input(from: PlayerId, input: RallyInput, now: Long) {
    val ball = state.ball ?: return
    if (status != GameStatus.Running || judged) return
    if (input.ball != ball.id || from != ball.to) return
    // A client's clock estimate can be slightly off, but never a second into the future.
    val at = input.at.coerceAtMost(now + CLOCK_SLACK_MS)
    val error = at - ball.arriveAt
    when {
      abs(error) <= ball.window + TOLERANCE_MS -> hit(ball, from, at, error.toInt())
      error <
        -(ball.window + IGNORE_BEFORE_MS) -> {} // Far too early to be an attempt at this ball.
      else -> miss(ball, from, early = error < 0, now)
    }
  }

  override fun tick(now: Long) {
    val ball = state.ball ?: return
    if (status != GameStatus.Running || judged) return
    if (now > ball.arriveAt + ball.window + TOLERANCE_MS + LATE_GRACE_MS)
      miss(ball, ball.to, early = null, now)
  }

  private fun hit(ball: Ball, player: PlayerId, at: Long, error: Int) {
    val streak = state.streak + 1
    state =
      state.copy(
        streak = streak,
        best = maxOf(state.best, streak),
        event = RallyEvent.Hit(ball.id, player, error),
      )
    if (streak >= state.target) {
      judged = true
      state = state.copy(ball = null)
      status = GameStatus.Won("${state.target} returns in a row", stats())
      return
    }
    launch(from = player, at = at)
  }

  private fun miss(ball: Ball, player: PlayerId, early: Boolean?, now: Long) {
    val lives = state.lives - 1
    state =
      state.copy(
        lives = lives,
        streak = 0,
        event = RallyEvent.Miss(ball.id, player, early),
        ball = null,
      )
    if (lives <= 0) {
      judged = true
      val reason =
        when (early) {
          true -> "swung too early"
          false -> "swung too late"
          null -> "missed the return"
        }
      status = GameStatus.Lost(reason, player, stats())
      return
    }
    laps.clear()
    serve(now + RECOVER_MS)
  }

  private fun serve(at: Long) = launch(from = null, at = at)

  private fun launch(from: PlayerId?, at: Long) {
    judged = false
    val progress = state.streak.toFloat() / state.target
    val travel = lerp(tuning.travelSlow, tuning.travelFast, progress)
    val window = lerp(tuning.windowWide, tuning.windowTight, progress)
    val to = next(from)
    state =
      state.copy(
        ball =
          Ball(
            (state.ball?.id ?: state.event?.ball ?: 0) + 1,
            from,
            to,
            at,
            at + travel.toLong(),
            window.toInt(),
          )
      )
  }

  /** Everyone gets the spark once per lap, in a fresh order, and never twice in a row. */
  private fun next(from: PlayerId?): PlayerId {
    if (laps.isEmpty()) laps += setup.players.shuffled(random)
    val pick = laps.firstOrNull { it != from } ?: laps.first()
    laps.remove(pick)
    return pick
  }

  private fun stats() =
    listOf(Stat("Best streak", state.best.toString()), Stat("Goal", "${state.target} in a row"))

  private class Tuning(
    val laps: Int,
    val lives: Int,
    val travelSlow: Float,
    val travelFast: Float,
    val windowWide: Float,
    val windowTight: Float,
  ) {
    companion object {
      fun of(d: Difficulty) =
        when (d) {
          Difficulty.EASY -> Tuning(2, 4, 1_700f, 1_100f, 320f, 210f)
          Difficulty.NORMAL -> Tuning(3, 3, 1_550f, 950f, 280f, 170f)
          Difficulty.HARD -> Tuning(4, 3, 1_400f, 820f, 240f, 140f)
          Difficulty.EXTREME -> Tuning(5, 2, 1_250f, 700f, 200f, 115f)
        }
    }
  }

  companion object {
    const val SERVE_DELAY_MS = 600L
    const val RECOVER_MS = 1_600L
    const val TOLERANCE_MS = 40
    const val LATE_GRACE_MS = 160
    const val IGNORE_BEFORE_MS = 450
    const val CLOCK_SLACK_MS = 60L

    fun target(players: Int, difficulty: Difficulty): Int =
      (players * Tuning.of(difficulty).laps).coerceIn(8, 40)

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
  }
}
