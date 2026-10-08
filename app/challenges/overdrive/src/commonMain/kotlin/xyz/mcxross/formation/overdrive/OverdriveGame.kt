package xyz.mcxross.formation.overdrive

import kotlin.random.Random
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeGame
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.GameStatus
import xyz.mcxross.formation.session.Stat

internal class OverdriveGame(setup: ChallengeSetup) : ChallengeGame<OverdriveState, Rotate> {
  private val random = Random(setup.seed)

  init {
    require(setup.players.size == 2 && setup.players.distinct().size == 2) {
      "Overdrive needs two distinct players"
    }
  }

  override var state: OverdriveState
    private set

  override var status: GameStatus = GameStatus.Running
    private set

  init {
    val dials =
      setup.players.map { player ->
        val edges = Symbol.entries.shuffled(random)
        val clue = nextSymbol(edges.first())
        Dial(
          player,
          edges,
          clue = clue,
          nextClue = nextSymbol(clue),
          launchAt = setup.startAt,
          catchAt = setup.startAt,
        )
      }
    state =
      OverdriveState(
        dials,
        1,
        setup.startAt,
        setup.startAt,
        startAt = setup.startAt,
        endsAt = setup.startAt + TIME_LIMIT_MS,
      )
    schedule(1, setup.startAt, dials.map { it.clue!! }, dials.map { it.nextClue!! })
  }

  override fun stateFor(player: PlayerId): OverdriveState {
    require(state.dials.any { it.player == player })
    return state.copy(
      dials =
        state.dials.map {
          if (it.player == player) it.copy(clue = null, nextClue = null) else it
        }
    )
  }

  override fun input(from: PlayerId, input: Rotate, now: Long) {
    if (state.dials.none { it.player == from }) return
    advance(now)
    // Trust the phone's touch time only within the late window, so a stamp can't buy extra time.
    val at = input.at.coerceIn(now - LATE_INPUT_MS, now)
    if (status != GameStatus.Running || at < state.waveAt || input.wave != state.wave) return
    val dial = state.dial(from)
    if (dial.result != Catch.Pending || at >= dial.catchAt || input.turn != dial.turns + 1) return
    state =
      state.copy(
        dials = state.dials.map { if (it.player == from) it.copy(turns = input.turn) else it }
      )
  }

  override fun tick(now: Long) = advance(now)

  private fun nextSymbol(previous: Symbol) = Symbol.entries.filter { it != previous }.random(random)

  private fun schedule(wave: Int, at: Long, targets: List<Symbol>, next: List<Symbol>) {
    val dials =
      state.dials.mapIndexed { index, dial ->
        val launch = at + index * if (wave <= STAGGERED_WAVES) STAGGER_MS else 0
        dial.copy(
          clue = targets[index],
          nextClue = next[index],
          launchAt = launch,
          catchAt = launch + fall(state.clears),
          result = Catch.Pending,
        )
      }
    state =
      state.copy(
        dials = dials,
        wave = wave,
        waveAt = at,
        nextWaveAt = dials.maxOf { it.catchAt } + gap(state.clears),
        preview = wave > 8,
      )
  }

  private fun advance(now: Long) {
    if (status != GameStatus.Running || now < state.waveAt) return
    val until = minOf(now, state.endsAt)
    val settled = now - LATE_INPUT_MS
    // Resolve scheduled catches before advancing waves, including a tick that crosses several
    // deadlines.
    // A catch settles once late taps for it can no longer arrive; the next wave still starts on
    // time
    // because every gap between waves outlasts that window.
    while (status == GameStatus.Running) {
      state =
        state.copy(
          dials =
            state.dials.map { dial ->
              if (
                dial.result == Catch.Pending &&
                  settled >= dial.catchAt &&
                  dial.catchAt <= state.endsAt
              ) {
                dial.copy(result = if (dial.facing() == dial.clue) Catch.Caught else Catch.Missed)
              } else dial
            }
        )
      if (state.dials.all { it.result != Catch.Pending } && state.lastWave?.wave != state.wave) {
        val cleared = state.dials.all { it.result == Catch.Caught }
        val at = state.dials.maxOf { it.catchAt }
        val clears = state.clears + if (cleared) 1 else 0
        state =
          state.copy(
            clears = clears,
            nextWaveAt = at + gap(clears),
            lastWave = WaveResult(state.wave, at, cleared),
          )
        if (!cleared) {
          status = GameStatus.Lost("The formation missed a wave.", stats = stats())
          return
        }
        if (state.clears == OverdriveState.REQUIRED_WAVES) {
          status = GameStatus.Won("Overdrive complete", stats(finishedAt = at))
          return
        }
      }
      if (state.lastWave?.wave != state.wave || until < state.nextWaveAt) break
      val targets = state.dials.map { it.nextClue!! }
      schedule(state.wave + 1, state.nextWaveAt, targets, targets.map(::nextSymbol))
    }
    if (settled >= state.endsAt)
      status = GameStatus.Lost("The formation ran out of time.", stats = stats())
  }

  private fun stats(finishedAt: Long? = null): List<Stat> {
    val stats = listOf(Stat("Waves", "${state.clears}/${OverdriveState.REQUIRED_WAVES}"))
    val tenths = finishedAt?.let { (state.endsAt - it + 99) / 100 } ?: return stats
    return stats + Stat("Time left", "${tenths / 10}.${tenths % 10}s")
  }

  companion object {
    const val LATE_INPUT_MS = 100L

    // Overdrive's one speed; change these to make every game faster or slower.
    // A clean run finishes about five seconds inside the time limit.
    const val TIME_LIMIT_MS = 28_000L
    const val FIRST_FALL_MS = 2_000L
    // Each cleared wave makes both pulses fall this much faster.
    const val SPEEDUP_PERCENT = 8
    // The opening waves launch the second pulse a little after the first.
    const val STAGGERED_WAVES = 4
    const val STAGGER_MS = 650L
    // The pause before the next wave, shrinking from the first to the last.
    const val FIRST_GAP_MS = 650L
    const val LAST_GAP_MS = 350L

    private const val STEPS = OverdriveState.REQUIRED_WAVES - 1

    fun fall(clears: Int): Long {
      var fall = FIRST_FALL_MS
      repeat(clears.coerceIn(0, STEPS)) { fall = fall * (100 - SPEEDUP_PERCENT) / 100 }
      return fall
    }

    fun gap(clears: Int): Long =
      FIRST_GAP_MS - (FIRST_GAP_MS - LAST_GAP_MS) * clears.coerceIn(0, STEPS) / STEPS
  }
}
