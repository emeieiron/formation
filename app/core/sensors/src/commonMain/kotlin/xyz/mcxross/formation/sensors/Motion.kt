package xyz.mcxross.formation.sensors

import kotlin.math.sqrt

// Phone frame, as Android reports it: x toward the right edge, y toward the top, z out of the
// screen.
data class Vec3(val x: Float, val y: Float, val z: Float) {
  val magnitude: Float
    get() = sqrt(x * x + y * y + z * z)

  companion object {
    val ZERO = Vec3(0f, 0f, 0f)
  }
}

const val STANDARD_GRAVITY = 9.80665f
