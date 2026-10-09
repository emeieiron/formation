package xyz.mcxross.formation.caravan

import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeGame
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.GameStatus
import xyz.mcxross.formation.session.Stat

class CaravanGame(private val setup: ChallengeSetup) : ChallengeGame<CaravanState, Stride> {

  companion object {
    const val MIN_PLAYERS = 6
  }

  init {
    require(setup.players.size >= MIN_PLAYERS) {
      "Caravan requires at least $MIN_PLAYERS players (got ${setup.players.size})"
    }
  }

  override var state: CaravanState =
    CaravanState(
      targetSteps = Pacing.TARGET_STEPS,
      startAt = setup.startAt,
      endsAt = setup.startAt + Pacing.LIMIT_MS,
      walkers = setup.players.map { WalkerState(player = it) },
      minSteps = 0,
      maxSteps = 0,
      groupCadence = 0f,
      pausedByPackRule = false,
      timeRemainingMs = Pacing.LIMIT_MS,
    )
    private set

  override var status: GameStatus = GameStatus.Running
    private set

  override fun input(from: PlayerId, input: Stride, now: Long) {
    if (status != GameStatus.Running || now < state.startAt) return
    // Input and timer events share the same deadline, regardless of their arrival order.
    if (now >= state.endsAt) {
      tick(now)
      return
    }

    val currentWalkers = state.walkers
    val walker = currentWalkers.firstOrNull { it.player == from } ?: return
    if (input.stepIndex <= walker.lastStrideIndex) return

    // Debounce to prevent accelerometer noise / frantic shaking
    if (walker.lastStepAt > 0L && now - walker.lastStepAt < Pacing.MIN_STEP_INTERVAL_MS) {
      return
    }

    val currentMin = currentWalkers.minOf { it.steps }

    // Pack rule: If a walker is more than MAX_SPREAD_STEPS ahead of the rear,
    // their steps do not advance until the pack catches up.
    if (walker.steps >= currentMin + Pacing.MAX_SPREAD_STEPS) {
      val updatedWalkers =
        currentWalkers.map {
          if (it.player == from) it.copy(status = WalkerStatus.WaitingForCaravan) else it
        }
      state = state.copy(walkers = updatedWalkers, pausedByPackRule = true)
      return
    }

    val deltaMs =
      if (walker.lastStepAt > 0L) (now - walker.lastStepAt).coerceAtLeast(300L) else 600L
    val instantaneousCadence = (1000f / deltaMs).coerceIn(0.5f, 3.5f)
    val smoothedCadence =
      if (walker.cadence > 0f) walker.cadence * 0.7f + instantaneousCadence * 0.3f
      else instantaneousCadence

    val nextSteps = walker.steps + 1

    val updatedWalkers =
      currentWalkers.map {
        if (it.player == from) {
          it.copy(
            steps = nextSteps,
            lastStepAt = now,
            lastStrideIndex = input.stepIndex,
            cadence = smoothedCadence,
            status =
              if (nextSteps >= state.targetSteps) WalkerStatus.Finished else WalkerStatus.Pacing,
          )
        } else it
      }

    val newMin = updatedWalkers.minOf { it.steps }
    val newMax = updatedWalkers.maxOf { it.steps }

    // Re-evaluate statuses for all squad members
    val finalWalkers =
      updatedWalkers.map { w ->
        val newStatus =
          when {
            w.steps >= state.targetSteps -> WalkerStatus.Finished
            w.steps >= newMin + Pacing.MAX_SPREAD_STEPS -> WalkerStatus.WaitingForCaravan
            w.steps == newMax && newMax > newMin + 2 -> WalkerStatus.Leading
            w.steps == newMin && newMax > newMin + 3 -> WalkerStatus.Lagging
            else -> WalkerStatus.Pacing
          }
        w.copy(status = newStatus)
      }

    val activeCadences = finalWalkers.map { it.cadence }.filter { it > 0f }
    val avgGroupCadence =
      if (activeCadences.isNotEmpty()) activeCadences.average().toFloat() else 0f

    state =
      state.copy(
        walkers = finalWalkers,
        minSteps = newMin,
        maxSteps = newMax,
        groupCadence = avgGroupCadence,
        pausedByPackRule = finalWalkers.any { it.status == WalkerStatus.WaitingForCaravan },
        timeRemainingMs = (state.endsAt - now).coerceAtLeast(0L),
      )

    // Check win condition: every player reached target steps
    if (newMin >= state.targetSteps) {
      val elapsedSec = ((now - state.startAt) / 1000L).coerceAtLeast(1L)
      status =
        GameStatus.Won(
          headline = "Caravan Reached Destination!",
          stats =
            listOf(
              Stat("Target Steps", "${state.targetSteps}"),
              Stat("Squad Size", "${setup.players.size} walkers"),
              Stat("Total Time", "${elapsedSec}s"),
            ),
        )
    }
  }

  override fun tick(now: Long) {
    if (status != GameStatus.Running) return

    val remaining = (state.endsAt - now).coerceAtLeast(0L)

    // Check cadence decay for inactive walkers
    var cadenceChanged = false
    val updatedWalkers =
      state.walkers.map { w ->
        if (
          w.cadence > 0f && w.lastStepAt > 0L && now - w.lastStepAt > Pacing.MAX_CADENCE_WINDOW_MS
        ) {
          cadenceChanged = true
          w.copy(cadence = 0f)
        } else w
      }

    if (cadenceChanged || remaining != state.timeRemainingMs) {
      val activeCadences = updatedWalkers.map { it.cadence }.filter { it > 0f }
      val avgCadence = if (activeCadences.isNotEmpty()) activeCadences.average().toFloat() else 0f
      state =
        state.copy(walkers = updatedWalkers, groupCadence = avgCadence, timeRemainingMs = remaining)
    }

    // Check timeout
    if (now >= state.endsAt && state.minSteps < state.targetSteps) {
      val slowest = state.walkers.minByOrNull { it.steps }?.player
      status =
        GameStatus.Lost(
          reason = "Caravan ran out of time",
          culprit = slowest,
          stats =
            listOf(
              Stat("Caravan Steps", "${state.minSteps} / ${state.targetSteps}"),
              Stat("Squad Size", "${setup.players.size} walkers"),
            ),
        )
    }
  }
}
