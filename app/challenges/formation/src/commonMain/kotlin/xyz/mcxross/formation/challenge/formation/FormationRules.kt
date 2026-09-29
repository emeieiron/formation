package xyz.mcxross.formation.challenge.formation

import kotlin.random.Random
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.sensors.Pose
import xyz.mcxross.formation.session.ChallengeGame
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.GameStatus
import xyz.mcxross.formation.session.Stat

@Serializable
data class Figure(
  val attempt: Int,
  val targets: Map<String, Pose>,
  val startedAt: Long,
  val deadline: Long,
)

@Serializable
sealed interface FigureEvent {
  val attempt: Int

  @Serializable
  @SerialName("locked")
  data class Locked(override val attempt: Int, val tookMs: Long) : FigureEvent

  @Serializable
  @SerialName("timeout")
  data class TimedOut(override val attempt: Int, val missing: List<PlayerId>) : FigureEvent
}

@Serializable
data class FormationState(
  val architect: PlayerId,
  val figures: Int,
  val done: Int = 0,
  val lives: Int,
  val maxLives: Int,
  val holdMs: Int,
  val figure: Figure? = null,
  val poses: Map<String, Pose> = emptyMap(),
  val holdFrom: Long? = null,
  val event: FigureEvent? = null,
) {
  fun inPlace(player: PlayerId): Boolean =
    figure?.targets?.get(player.value)?.let { it == poses[player.value] } == true
}

@Serializable data class FormationInput(val pose: Pose)

internal class FormationGame(private val setup: ChallengeSetup) :
  ChallengeGame<FormationState, FormationInput> {
  private val random = Random(setup.seed)
  private val tuning = Tuning.of(setup.difficulty)
  private var attempts = 0
  private var nextAt: Long? = null

  override var state =
    FormationState(
      architect = setup.seeker,
      figures = tuning.figures,
      lives = tuning.lives,
      maxLives = tuning.lives,
      holdMs = tuning.holdMs,
    )
    private set

  override var status: GameStatus = GameStatus.Running
    private set

  init {
    begin(setup.startAt)
  }

  override fun input(from: PlayerId, input: FormationInput, now: Long) {
    if (from !in setup.players || status != GameStatus.Running) return
    state = state.copy(poses = state.poses + (from.value to input.pose))
    settle(now)
  }

  override fun tick(now: Long) {
    if (status != GameStatus.Running) return
    nextAt?.let {
      if (now >= it) {
        nextAt = null
        begin(now)
      }
      return
    }
    val figure = state.figure ?: return
    val holdFrom = state.holdFrom
    when {
      holdFrom != null && now - holdFrom >= state.holdMs -> lock(figure, now)
      now > figure.deadline -> timeout(figure, now)
    }
  }

  private fun settle(now: Long) {
    val figure = state.figure ?: return
    val all = figure.targets.all { (p, pose) -> state.poses[p] == pose }
    state =
      when {
        all && state.holdFrom == null -> state.copy(holdFrom = now)
        !all && state.holdFrom != null -> state.copy(holdFrom = null)
        else -> state
      }
  }

  private fun lock(figure: Figure, now: Long) {
    val done = state.done + 1
    state =
      state.copy(
        done = done,
        figure = null,
        holdFrom = null,
        event = FigureEvent.Locked(figure.attempt, now - figure.startedAt),
      )
    if (done >= state.figures) {
      status =
        GameStatus.Won("$done figures built", listOf(Stat("Figures", "$done of ${state.figures}")))
      return
    }
    nextAt = now + PAUSE_MS
  }

  private fun timeout(figure: Figure, now: Long) {
    val missing = figure.targets.filter { (p, pose) -> state.poses[p] != pose }.keys.map(::PlayerId)
    val lives = state.lives - 1
    state =
      state.copy(
        lives = lives,
        figure = null,
        holdFrom = null,
        event = FigureEvent.TimedOut(figure.attempt, missing),
      )
    if (lives <= 0) {
      status =
        GameStatus.Lost(
          "wasn't in position",
          missing.firstOrNull(),
          listOf(Stat("Figures", "${state.done} of ${state.figures}")),
        )
      return
    }
    nextAt = now + PAUSE_MS
  }

  private fun begin(at: Long) {
    attempts += 1
    val builders = setup.players.filter { it != setup.seeker }
    var targets: Map<String, Pose>
    do {
      targets = builders.associate { p ->
        // Everyone has to move: never the pose they are already in.
        val options = BUILDER_POSES.filter { it != state.poses[p.value] }
        p.value to options[random.nextInt(options.size)]
      }
    } while (builders.size >= 2 && targets.values.toSet().size < 2)
    val progress = state.done.toFloat() / state.figures
    val limit = tuning.limitSlow + (tuning.limitFast - tuning.limitSlow) * progress
    state =
      state.copy(
        figure =
          Figure(attempts, targets + (setup.seeker.value to Pose.FACE_UP), at, at + limit.toLong()),
        holdFrom = null,
      )
    settle(at)
  }

  private class Tuning(
    val figures: Int,
    val lives: Int,
    val holdMs: Int,
    val limitSlow: Float,
    val limitFast: Float,
  ) {
    companion object {
      fun of(d: Difficulty) =
        when (d) {
          Difficulty.EASY -> Tuning(3, 4, 1_200, 34_000f, 24_000f)
          Difficulty.NORMAL -> Tuning(4, 3, 1_500, 30_000f, 18_000f)
          Difficulty.HARD -> Tuning(5, 3, 1_800, 26_000f, 15_000f)
          Difficulty.EXTREME -> Tuning(6, 2, 2_000, 22_000f, 12_000f)
        }
    }
  }

  companion object {
    const val PAUSE_MS = 2_400L
    val BUILDER_POSES =
      listOf(
        Pose.UPRIGHT,
        Pose.FACE_DOWN,
        Pose.SIDEWAYS_LEFT,
        Pose.SIDEWAYS_RIGHT,
        Pose.UPSIDE_DOWN,
        Pose.FACE_UP,
      )

    fun figures(difficulty: Difficulty) = Tuning.of(difficulty).figures
  }
}
