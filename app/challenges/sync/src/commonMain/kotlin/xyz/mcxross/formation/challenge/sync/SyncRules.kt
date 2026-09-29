package xyz.mcxross.formation.challenge.sync

import kotlin.math.abs
import kotlin.random.Random
import kotlinx.serialization.Serializable
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeGame
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.GameStatus
import xyz.mcxross.formation.session.Stat

// Widens the window for moves the sensors detect late.
enum class SyncTask(val verb: String, val hint: String, val extraMs: Int, val motion: Boolean) {
  TAP("Tap", "One tap, right on the beat", 0, false),
  DOUBLE_TAP("Double tap", "Two quick taps; the second lands on the beat", 60, false),
  SWIPE_UP("Swipe up", "Flick upwards on the beat", 60, false),
  SWIPE_DOWN("Swipe down", "Flick downwards on the beat", 60, false),
  SHAKE("Shake", "Shake your phone on the beat", 220, true),
  FLIP("Flip", "Lay it face down on the beat", 260, true),
  TURN("Turn", "Turn it on its side on the beat", 240, true),
  COVER("Cover", "Cover the top of your phone on the beat", 160, true),
}

@Serializable
data class SyncRound(
  val attempt: Int,
  val countFrom: Long,
  val moment: Long,
  val blindFrom: Long? = null,
  val window: Int,
  val tasks: Map<String, SyncTask>,
)

// Each player's offset from the moment in ms; null if they never moved.
@Serializable
data class SyncResult(
  val attempt: Int,
  val passed: Boolean,
  val offsets: Map<String, Int?>,
  val window: Int,
  val culprit: PlayerId? = null,
)

@Serializable
data class SyncState(
  val rounds: Int,
  val done: Int = 0,
  val lives: Int,
  val maxLives: Int,
  val round: SyncRound? = null,
  val acted: List<String> = emptyList(),
  val result: SyncResult? = null,
)

@Serializable data class SyncInput(val attempt: Int, val at: Long)

internal class SyncGame(private val setup: ChallengeSetup) : ChallengeGame<SyncState, SyncInput> {
  private val random = Random(setup.seed)
  private val tuning = Tuning.of(setup.difficulty)
  private val times = mutableMapOf<String, Long>()
  private var attempts = 0
  private var nextAt: Long? = null

  override var state =
    SyncState(rounds = tuning.rounds, lives = tuning.lives, maxLives = tuning.lives)
    private set

  override var status: GameStatus = GameStatus.Running
    private set

  init {
    begin(setup.startAt)
  }

  override fun input(from: PlayerId, input: SyncInput, now: Long) {
    val round = state.round ?: return
    if (input.attempt != round.attempt || from.value in times) return
    if (from.value !in round.tasks) return
    if (input.at < round.countFrom - EARLY_MS) return
    times[from.value] = input.at.coerceAtMost(now + CLOCK_SLACK_MS)
    state = state.copy(acted = times.keys.toList())
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
    val round = state.round ?: return
    val longest = round.tasks.values.maxOf { it.extraMs }
    if (
      now >= round.moment + round.window + longest + JUDGE_DELAY_MS ||
        times.size == round.tasks.size && now >= round.moment + round.window
    ) {
      judge(round, now)
    }
  }

  private fun judge(round: SyncRound, now: Long) {
    val offsets =
      round.tasks.keys.associateWith { p -> times[p]?.let { (it - round.moment).toInt() } }
    fun off(p: String): Int =
      offsets[p]?.let { abs(it) - round.tasks.getValue(p).extraMs } ?: Int.MAX_VALUE
    val passed = round.tasks.keys.all { off(it) <= round.window }
    val culprit = if (passed) null else round.tasks.keys.maxBy(::off).let(::PlayerId)
    val result = SyncResult(round.attempt, passed, offsets, round.window, culprit)
    if (passed) {
      val done = state.done + 1
      state = state.copy(done = done, round = null, result = result, acted = emptyList())
      if (done >= state.rounds) {
        status = GameStatus.Won("$done rounds in perfect sync", stats(offsets))
        return
      }
    } else {
      val lives = state.lives - 1
      state = state.copy(lives = lives, round = null, result = result, acted = emptyList())
      if (lives <= 0) {
        val offset = culprit?.let { offsets[it.value] }
        val reason =
          when {
            offset == null -> "never made their move"
            offset < 0 -> "was ${-offset} ms early"
            else -> "was $offset ms late"
          }
        status = GameStatus.Lost(reason, culprit, stats(offsets))
        return
      }
    }
    nextAt = now + RESULT_MS
  }

  private fun begin(at: Long) {
    times.clear()
    attempts += 1
    val progress = state.done.toFloat() / state.rounds
    val countFrom = at + INTRO_MS
    val moment = countFrom + BEATS * BEAT_MS
    val window = tuning.windowWide + (tuning.windowTight - tuning.windowWide) * progress
    // The second half of the rounds hide the final beats: the group counts them out loud.
    val blind = state.done >= (state.rounds + 1) / 2
    val pool = SyncTask.entries.filter { state.done > 0 || !it.motion }.shuffled(random)
    val tasks = setup.players.mapIndexed { i, p -> p.value to pool[i % pool.size] }.toMap()
    state =
      state.copy(
        round =
          SyncRound(
            attempts,
            countFrom,
            moment,
            if (blind) moment - BLIND_MS else null,
            window.toInt(),
            tasks,
          ),
        acted = emptyList(),
      )
  }

  private fun stats(offsets: Map<String, Int?>): List<Stat> {
    val landed = offsets.values.filterNotNull()
    val spread = if (landed.size >= 2) "${landed.max() - landed.min()} ms" else "–"
    return listOf(Stat("Rounds", "${state.done} of ${state.rounds}"), Stat("Last spread", spread))
  }

  private class Tuning(
    val rounds: Int,
    val lives: Int,
    val windowWide: Float,
    val windowTight: Float,
  ) {
    companion object {
      fun of(d: Difficulty) =
        when (d) {
          Difficulty.EASY -> Tuning(3, 4, 380f, 240f)
          Difficulty.NORMAL -> Tuning(4, 3, 320f, 180f)
          Difficulty.HARD -> Tuning(5, 3, 280f, 150f)
          Difficulty.EXTREME -> Tuning(6, 2, 240f, 120f)
        }
    }
  }

  companion object {
    const val INTRO_MS = 1_800L
    const val BEATS = 3
    const val BEAT_MS = 1_000L
    const val BLIND_MS = 1_600L
    const val RESULT_MS = 2_800L
    const val EARLY_MS = 400L
    const val JUDGE_DELAY_MS = 250L
    const val CLOCK_SLACK_MS = 60L

    fun rounds(difficulty: Difficulty) = Tuning.of(difficulty).rounds
  }
}
