package xyz.mcxross.formation.ricochet.ui

import xyz.mcxross.formation.ricochet.Physics
import xyz.mcxross.formation.ricochet.Pulse
import xyz.mcxross.formation.ricochet.RicochetState

internal fun presentedPulse(state: RicochetState, now: Long): Pulse {
  if (state.finishedAt != null || now <= state.serveAt) return state.pulse
  val seconds = (now - maxOf(state.at, state.serveAt)).coerceIn(0, 100) / 1_000.0
  return Physics.step(state.pulse, state.targets, state.paddles, state.paddleHeight, seconds).pulse
}

internal data class Viewport(val x: Float, val y: Float, val scale: Float) {
  fun arenaY(screenY: Float) = (screenY - y) / scale.toDouble()
}

internal fun viewport(width: Float, height: Float): Viewport {
  val scale = minOf(width, height / xyz.mcxross.formation.ricochet.Arena.HEIGHT.toFloat())
  return Viewport((width - scale) / 2, (height - scale * xyz.mcxross.formation.ricochet.Arena.HEIGHT.toFloat()) / 2, scale)
}
