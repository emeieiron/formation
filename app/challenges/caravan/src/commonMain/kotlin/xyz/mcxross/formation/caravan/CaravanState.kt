package xyz.mcxross.formation.caravan

import kotlinx.serialization.Serializable
import xyz.mcxross.formation.model.PlayerId

@Serializable
enum class WalkerStatus {
  Pacing,
  Leading,
  Lagging,
  WaitingForCaravan,
  Finished,
}

@Serializable
data class WalkerState(
  val player: PlayerId,
  val steps: Int = 0,
  val lastStepAt: Long = 0L,
  val lastStrideIndex: Int = 0,
  val cadence: Float = 0f, // Steps per second
  val status: WalkerStatus = WalkerStatus.Pacing,
)

@Serializable
data class CaravanState(
  val targetSteps: Int = Pacing.TARGET_STEPS,
  val startAt: Long,
  val endsAt: Long,
  val walkers: List<WalkerState>,
  val minSteps: Int = 0,
  val maxSteps: Int = 0,
  val groupCadence: Float = 0f,
  val pausedByPackRule: Boolean = false,
  val timeRemainingMs: Long = 0L,
) {
  val isComplete: Boolean
    get() = minSteps >= targetSteps

  val progress: Float
    get() = if (targetSteps > 0) (minSteps.toFloat() / targetSteps).coerceIn(0f, 1f) else 0f

  fun walker(player: PlayerId): WalkerState =
    walkers.firstOrNull { it.player == player } ?: WalkerState(player)
}

@Serializable data class Stride(val stepIndex: Int, val at: Long)

object Pacing {
  const val TARGET_STEPS = 15_000
  const val LIMIT_MS = 3 * 3600 * 1000L // 3 hours
  const val MAX_SPREAD_STEPS = 30
  const val MIN_STEP_INTERVAL_MS = 280L
  const val MAX_CADENCE_WINDOW_MS = 3_000L
}
