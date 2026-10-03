package xyz.mcxross.formation.overdrive

import kotlin.random.Random
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeGame
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.GameStatus
import xyz.mcxross.formation.session.Stat

internal class OverdriveGame(setup: ChallengeSetup) : ChallengeGame<OverdriveState, Rotate> {
  private val random = Random(setup.seed)
  private val pacing = Pacing(setup.difficulty)

  init {
    require(setup.players.size == 2 && setup.players.distinct().size == 2) { "Overdrive needs two distinct players" }
  }

  override var state: OverdriveState
    private set

  override var status: GameStatus = GameStatus.Running
    private set

  init {
    val dials = setup.players.map { player ->
      val edges = Symbol.entries.shuffled(random)
      val clue = nextSymbol(edges.first())
      Dial(player, edges, clue = clue, nextClue = nextSymbol(clue),
        launchAt = setup.startAt, catchAt = setup.startAt)
    }
    state = OverdriveState(dials, 1, setup.startAt, setup.startAt,
      setup.startAt + OverdriveState.LIMIT_MS)
    schedule(1, setup.startAt, dials.map { it.clue!! }, dials.map { it.nextClue!! })
  }

  override fun stateFor(player: PlayerId): OverdriveState {
    require(state.dials.any { it.player == player })
    return state.copy(dials = state.dials.map {
      if (it.player == player) it.copy(clue = null, nextClue = null) else it
    })
  }

  override fun input(from: PlayerId, input: Rotate, now: Long) {
    if (state.dials.none { it.player == from }) return
    advance(now)
    if (status != GameStatus.Running || now < state.waveAt || input.wave != state.wave) return
    val dial = state.dial(from)
    if (dial.result != Catch.Pending || now >= dial.catchAt || input.turn != dial.turns + 1) return
    state = state.copy(dials = state.dials.map { if (it.player == from) it.copy(turns = input.turn) else it })
  }

  override fun tick(now: Long) = advance(now)

  private fun nextSymbol(previous: Symbol) = Symbol.entries.filter { it != previous }.random(random)

  private fun schedule(wave: Int, at: Long, targets: List<Symbol>, next: List<Symbol>) {
    val dials = state.dials.mapIndexed { index, dial ->
      val launch = at + index * pacing.stagger(wave)
      dial.copy(clue = targets[index], nextClue = next[index], launchAt = launch,
        catchAt = launch + pacing.flight(dial.matches), result = Catch.Pending)
    }
    state = state.copy(dials = dials, wave = wave, waveAt = at,
      nextWaveAt = dials.maxOf { it.catchAt } + pacing.reset(true), preview = wave > 8)
  }

  private fun advance(now: Long) {
    if (status != GameStatus.Running || now < state.waveAt) return
    val until = minOf(now, state.endsAt)
    // Resolve scheduled catches before advancing waves, including a tick that crosses several deadlines.
    while (status == GameStatus.Running) {
      state = state.copy(dials = state.dials.map { dial ->
        if (dial.result == Catch.Pending && until >= dial.catchAt) {
          val matched = dial.facing() == dial.clue
          dial.copy(result = if (matched) Catch.Caught else Catch.Missed,
            matches = dial.matches + if (matched) 1 else 0)
        } else dial
      })
      if (state.dials.all { it.result != Catch.Pending } && state.lastWave?.wave != state.wave) {
        val cleared = state.dials.all { it.result == Catch.Caught }
        val at = state.dials.maxOf { it.catchAt }
        state = state.copy(clears = state.clears + if (cleared) 1 else 0,
          misses = state.misses + if (cleared) 0 else 1,
          nextWaveAt = at + pacing.reset(cleared), lastWave = WaveResult(state.wave, at, cleared))
        if (state.clears == OverdriveState.REQUIRED_WAVES) {
          status = GameStatus.Won("Overdrive complete", listOf(
            Stat("Waves", "${state.clears}"), Stat("Misses", "${state.misses}")))
          return
        }
        if (state.misses == OverdriveState.MAX_MISSES) {
          status = GameStatus.Lost("The formation missed three waves.")
          return
        }
      }
      if (state.lastWave?.wave != state.wave || until < state.nextWaveAt) break
      val targets = state.dials.map { it.nextClue!! }
      schedule(state.wave + 1, state.nextWaveAt, targets, targets.map(::nextSymbol))
    }
    if (now >= state.endsAt) status = GameStatus.Lost("The formation ran out of time.")
  }
}
