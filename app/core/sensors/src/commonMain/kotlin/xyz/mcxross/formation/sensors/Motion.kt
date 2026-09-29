package xyz.mcxross.formation.sensors

import kotlin.math.sqrt
import kotlinx.coroutines.flow.Flow

// Phone frame, as Android reports it: x toward the right edge, y toward the top, z out of the
// screen.
data class Vec3(val x: Float, val y: Float, val z: Float) {
  val magnitude: Float
    get() = sqrt(x * x + y * y + z * z)

  companion object {
    val ZERO = Vec3(0f, 0f, 0f)
  }
}

enum class MotionSensor {
  ACCELEROMETER,
  GYROSCOPE,
  PROXIMITY,
}

// Each flow keeps its sensor on only while collected.
interface Motion {
  val available: Set<MotionSensor>

  // Lying face up, z is about +9.8.
  val gravity: Flow<Vec3>

  val linearAcceleration: Flow<Vec3>

  val proximity: Flow<Boolean>
}

object NoMotion : Motion {
  override val available: Set<MotionSensor> = emptySet()
  override val gravity: Flow<Vec3> = kotlinx.coroutines.flow.emptyFlow()
  override val linearAcceleration: Flow<Vec3> = kotlinx.coroutines.flow.emptyFlow()
  override val proximity: Flow<Boolean> = kotlinx.coroutines.flow.emptyFlow()
}

const val STANDARD_GRAVITY = 9.80665f
