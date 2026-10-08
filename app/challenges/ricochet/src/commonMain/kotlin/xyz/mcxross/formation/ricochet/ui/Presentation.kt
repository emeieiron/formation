package xyz.mcxross.formation.ricochet.ui

import xyz.mcxross.formation.ricochet.Flight
import xyz.mcxross.formation.ricochet.Physics
import xyz.mcxross.formation.ricochet.Pulse
import xyz.mcxross.formation.ricochet.RicochetState

internal fun presentedPulse(state: RicochetState, now: Long): Pulse {
  return presentedFlight(state, now).pulse
}

internal fun presentedFlight(state: RicochetState, now: Long): Flight {
  if (state.finishedAt != null || now <= state.serveAt) {
    return Flight(state.pulse, state.targets, emptyList(), false, state.momentum, state.charge)
  }
  val seconds = (now - maxOf(state.at, state.serveAt)).coerceIn(0, 100) / 1_000.0
  return Physics.step(
    state.pulse,
    state.targets,
    state.paddles,
    state.paddleHeight,
    seconds,
    state.momentum,
    state.charge,
  )
}

internal data class Viewport(val x: Float, val y: Float, val scale: Float) {
  fun arenaY(screenY: Float) = (screenY - y) / scale.toDouble()
}

internal fun viewport(width: Float, height: Float): Viewport {
  val scale = minOf(width, height / xyz.mcxross.formation.ricochet.Arena.HEIGHT.toFloat())
  return Viewport(
    (width - scale) / 2,
    (height - scale * xyz.mcxross.formation.ricochet.Arena.HEIGHT.toFloat()) / 2,
    scale,
  )
}
