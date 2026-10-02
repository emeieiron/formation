package xyz.mcxross.formation.sensors.api

enum class SensorKind { ACCELERATION, GRAVITY, LINEAR_ACCELERATION, ANGULAR_VELOCITY, GAME_ROTATION, PROXIMITY, LIGHT }
enum class SensorSource { PLATFORM, ESTIMATED, SIMULATED }
enum class SensorQuality { UNKNOWN, USABLE, DEGRADED, UNRELIABLE }
enum class RecoveryAction { RETRY, REQUEST_PERMISSION, OPEN_SETTINGS }
enum class UnavailableReason { MISSING_HARDWARE, UNSUPPORTED_PLATFORM, PERMISSION_REQUIRED, SYSTEM_RESTRICTION }

sealed interface Availability {
  data object Checking : Availability
  data class Available(val source: SensorSource) : Availability
  data class Unavailable(val reason: UnavailableReason, val recovery: RecoveryAction? = null) : Availability
}

sealed interface Acquisition {
  data object Idle : Acquisition
  data object Starting : Acquisition
  data object Active : Acquisition
  data object Suspended : Acquisition
  data class Failed(val reason: FailureReason, val recovery: RecoveryAction = RecoveryAction.RETRY) : Acquisition
}

enum class FailureReason { REGISTRATION, NO_READINGS, INVALID_READING, UNAVAILABLE }

data class SamplingRequest(val periodUs: Int) {
  init { require(periodUs > 0) }
  companion object {
    val Game = SamplingRequest(20_000)
    val Ui = SamplingRequest(60_000)
  }
}

data class SensorSample<T>(
  val value: T,
  val timestampNanos: Long,
  val quality: SensorQuality,
  val source: SensorSource,
)

sealed interface SensorUpdate<out T> {
  data class Reading<T>(val sample: SensorSample<T>) : SensorUpdate<T>
  data class State(val acquisition: Acquisition) : SensorUpdate<Nothing>
  data object Gap : SensorUpdate<Nothing>
}

data class Rotation(val x: Float, val y: Float, val z: Float, val w: Float)
