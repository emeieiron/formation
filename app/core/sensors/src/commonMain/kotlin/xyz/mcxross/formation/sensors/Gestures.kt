package xyz.mcxross.formation.sensors

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

enum class Pose(val label: String) {
  FACE_UP("Face up"),
  FACE_DOWN("Face down"),
  UPRIGHT("Upright"),
  UPSIDE_DOWN("Upside down"),
  SIDEWAYS_LEFT("Sideways left"),
  SIDEWAYS_RIGHT("Sideways right"),
  TILTED("Tilted");

  val sideways: Boolean
    get() = this == SIDEWAYS_LEFT || this == SIDEWAYS_RIGHT
}

// Degrees; roll is positive with the right edge up.
data class Tilt(val roll: Float, val pitch: Float) {
  companion object {
    val LEVEL = Tilt(0f, 0f)

    fun of(gravity: Vec3): Tilt {
      val roll = atan2(gravity.x, sqrt(gravity.y * gravity.y + gravity.z * gravity.z))
      val pitch = atan2(gravity.y, gravity.z)
      return Tilt(roll.toDegrees(), pitch.toDegrees())
    }
  }
}

enum class Gesture {
  SHAKE,
  SWING,
  COVER,
}

private fun Float.toDegrees() = (this * 180.0 / PI).toFloat()

class PoseReader(private val settleMs: Long = 140) {
  private var current = Pose.TILTED
  private var candidate = Pose.TILTED
  private var candidateSince = 0L

  fun onGravity(g: Vec3, atMs: Long): Pose {
    val raw = classify(g)
    if (raw != candidate) {
      candidate = raw
      candidateSince = atMs
    }
    if (candidate != current && atMs - candidateSince >= settleMs) current = candidate
    return current
  }

  companion object {
    private const val DOMINANCE = 0.78f

    fun classify(g: Vec3): Pose {
      val m = g.magnitude.takeIf { it > 1f } ?: return Pose.TILTED
      val (ax, ay, az) = Triple(abs(g.x) / m, abs(g.y) / m, abs(g.z) / m)
      return when {
        az >= DOMINANCE -> if (g.z > 0) Pose.FACE_UP else Pose.FACE_DOWN
        ay >= DOMINANCE -> if (g.y > 0) Pose.UPRIGHT else Pose.UPSIDE_DOWN
        ax >= DOMINANCE -> if (g.x > 0) Pose.SIDEWAYS_LEFT else Pose.SIDEWAYS_RIGHT
        else -> Pose.TILTED
      }
    }
  }
}

class JoltReader(
  private val swingThreshold: Float = 15f,
  private val shakeThreshold: Float = 11f,
  private val shakePeaks: Int = 3,
  private val shakeWindowMs: Long = 900,
  private val refractoryMs: Long = 450,
) {
  private val peaks = ArrayDeque<Long>()
  private var above = false
  private var lastSwing = Long.MIN_VALUE / 2
  private var lastShake = Long.MIN_VALUE / 2

  fun onAcceleration(a: Vec3, atMs: Long): List<Gesture> {
    val m = a.magnitude
    val out = ArrayList<Gesture>(1)
    val rising = m >= shakeThreshold && !above
    above = m >= shakeThreshold * 0.7f && (above || m >= shakeThreshold)
    if (rising) {
      peaks.addLast(atMs)
      while (peaks.isNotEmpty() && atMs - peaks.first() > shakeWindowMs) peaks.removeFirst()
      if (peaks.size >= shakePeaks && atMs - lastShake > refractoryMs) {
        lastShake = atMs
        peaks.clear()
        out += Gesture.SHAKE
      }
    }
    if (m >= swingThreshold && atMs - lastSwing > refractoryMs) {
      lastSwing = atMs
      out += Gesture.SWING
    }
    return out
  }
}
