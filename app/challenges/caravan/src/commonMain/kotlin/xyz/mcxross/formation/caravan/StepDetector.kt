package xyz.mcxross.formation.caravan

import kotlin.math.abs
import xyz.mcxross.formation.sensors.STANDARD_GRAVITY
import xyz.mcxross.formation.sensors.Vec3

class StepDetector(
  private val linearThreshold: Float = 2.2f,
  private val gravityThreshold: Float = 2.0f,
  private val refractoryMs: Long = 300L,
) {
  private var lastStepAt: Long = 0L
  private var armed: Boolean = false
  private var previousMagnitude: Float = 0f

  fun onLinearAcceleration(a: Vec3, atMs: Long): Boolean {
    val m = a.magnitude
    return processMagnitude(m, linearThreshold, atMs)
  }

  fun onAcceleration(a: Vec3, atMs: Long): Boolean {
    val deviation = abs(a.magnitude - STANDARD_GRAVITY)
    return processMagnitude(deviation, gravityThreshold, atMs)
  }

  private fun processMagnitude(m: Float, threshold: Float, atMs: Long): Boolean {
    if (atMs - lastStepAt < refractoryMs) {
      previousMagnitude = m
      return false
    }

    var stepDetected = false
    if (!armed && m >= threshold) {
      armed = true
    } else if (armed && m < previousMagnitude && previousMagnitude >= threshold) {
      // Peak detected on descent
      armed = false
      lastStepAt = atMs
      stepDetected = true
    } else if (armed && m < threshold * 0.6f) {
      // Fell below re-arm threshold
      armed = false
    }

    previousMagnitude = m
    return stepDetected
  }

  fun reset() {
    lastStepAt = 0L
    armed = false
    previousMagnitude = 0f
  }
}
