package xyz.mcxross.formation.ricochet

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class Collision(
  val time: Double,
  val nx: Double,
  val ny: Double,
  val kind: ImpactKind,
  val side: Int? = null,
  val target: Int? = null,
)

// Sweep the pulse centre against an expanded box rather than sampling overlaps at the next frame.
internal fun targetCollision(pulse: Pulse, target: Target): Collision? {
  val halfWidth = Arena.TARGET_WIDTH / 2 + Arena.RADIUS
  val halfHeight = Arena.TARGET_HEIGHT / 2 + Arena.RADIUS
  fun slab(p: Double, v: Double, low: Double, high: Double): Pair<Double, Double>? {
    if (abs(v) < 1e-12)
      return if (p in low..high) Double.NEGATIVE_INFINITY to Double.POSITIVE_INFINITY else null
    val a = (low - p) / v
    val b = (high - p) / v
    return min(a, b) to max(a, b)
  }
  val x = slab(pulse.x, pulse.vx, target.x - halfWidth, target.x + halfWidth) ?: return null
  val y = slab(pulse.y, pulse.vy, target.y - halfHeight, target.y + halfHeight) ?: return null
  val enter = max(x.first, y.first)
  if (enter < 0 || enter > min(x.second, y.second)) return null
  return if (x.first > y.first) {
    Collision(enter, if (pulse.vx > 0) -1.0 else 1.0, 0.0, ImpactKind.Target, target = target.id)
  } else {
    Collision(enter, 0.0, if (pulse.vy > 0) -1.0 else 1.0, ImpactKind.Target, target = target.id)
  }
}
