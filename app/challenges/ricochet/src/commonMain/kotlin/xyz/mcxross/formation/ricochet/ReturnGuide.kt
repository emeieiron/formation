package xyz.mcxross.formation.ricochet

import kotlin.math.abs
import kotlin.math.atan2
import xyz.mcxross.formation.model.PlayerId

internal data class Landing(val side: Int, val x: Double, val y: Double)

internal object ReturnGuide {
  fun position(state: RicochetState, player: PlayerId): Double? {
    val landing = landing(state) ?: return null
    if (state.paddle(player).side != landing.side) return null
    val ahead = state.targets.filter { (it.x - landing.x) * (if (landing.side == 0) 1 else -1) > 0 }
    val otherHalf = ahead.filter { it.x.toInt() != landing.side }.ifEmpty { ahead }
    val angles = otherHalf.flatMap { target ->
      listOf(target.y, 2 * Arena.RADIUS - target.y, 2 * (Arena.HEIGHT - Arena.RADIUS) - target.y).map { y ->
        atan2(y - landing.y, abs(target.x - landing.x))
      }
    }
    val feasible = angles.filter { abs(it) <= Arena.MAX_ANGLE }
    val angle = (feasible.ifEmpty { angles }.minByOrNull { abs(it) } ?: 0.0).coerceIn(-Arena.MAX_ANGLE, Arena.MAX_ANGLE)
    return Arena.paddleY(landing.y - angle / Arena.MAX_ANGLE * state.paddleHeight / 2, state.paddleHeight)
  }

  // Trace only the published arena to the next outer paddle, including intervening walls and targets.
  fun landing(state: RicochetState): Landing? {
    var pulse = state.pulse
    var targets = state.targets
    repeat(24) {
      val hit = Physics.next(pulse, targets, state.paddles, state.paddleHeight, catchAll = true) ?: return null
      pulse = Physics.travel(pulse, hit.time)
      if (hit.kind == ImpactKind.Paddle) return Landing(hit.side!!, pulse.x, pulse.y)
      if (hit.kind == ImpactKind.Miss) return null
      if (hit.kind == ImpactKind.Target) targets = targets.filter { it.id != hit.target }
      pulse = Physics.reflect(pulse, hit, state.paddles, state.paddleHeight)
    }
    return null
  }
}
