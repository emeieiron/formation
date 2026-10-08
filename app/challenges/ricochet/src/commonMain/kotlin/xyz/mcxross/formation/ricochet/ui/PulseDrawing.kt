package xyz.mcxross.formation.ricochet.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import xyz.mcxross.formation.design.tokens.Colors
import xyz.mcxross.formation.ricochet.Arena
import xyz.mcxross.formation.ricochet.Charge
import xyz.mcxross.formation.ricochet.Momentum
import xyz.mcxross.formation.ricochet.RicochetState

internal fun DrawScope.drawPulse(state: RicochetState, side: Int, now: Long, colors: Colors) {
  if (now < state.serveAt && state.finishedAt == null) {
    val seconds = ((state.serveAt - now + 999) / 1_000).coerceIn(1, 2).toInt()
    val phase = ((now - state.serveAt + 1_200).coerceAtLeast(0) % 500) / 500f
    repeat(seconds) {
      drawCircle(
        colors.content.copy(alpha = 0.4f + phase * 0.3f),
        0.014f,
        Offset(0.48f + it * 0.05f, Arena.HEIGHT.toFloat() / 2),
      )
    }
    return
  }
  val flight = presentedFlight(state, now)
  val pulse = flight.pulse
  val at = Offset((pulse.x - side).toFloat(), pulse.y.toFloat())
  val pace = ((flight.momentum.factor - 1) / (Momentum.MAX_FACTOR - 1)).toFloat().coerceIn(0f, 1f)
  val tailSeconds = 0.10 + pace * 0.05
  val tail =
    Offset(at.x - (pulse.vx * tailSeconds).toFloat(), at.y - (pulse.vy * tailSeconds).toFloat())
  if (flight.charge.armed)
    drawLine(colors.accent.copy(alpha = 0.5f), tail, at, 0.016f, StrokeCap.Round)
  drawLine(
    colors.content.copy(alpha = 0.25f + pace * 0.25f),
    tail,
    at,
    0.006f + pace * 0.004f,
    StrokeCap.Round,
  )
  drawCircle(colors.content, Arena.RADIUS.toFloat(), at)
  if (flight.charge.progress > 0) {
    val radius = Arena.RADIUS.toFloat() + 0.009f
    drawArc(
      colors.accent,
      -90f,
      360f * flight.charge.progress / Charge.EXCHANGES,
      false,
      at - Offset(radius, radius),
      Size(radius * 2, radius * 2),
      style = Stroke(0.003f),
    )
    if (flight.charge.armed) drawCircle(colors.accent, 0.007f, at)
  }
  val edge = if (side == 0) 0.995f else 0.005f
  val approach = (1 - kotlin.math.abs(pulse.x - 1.0) / 0.35).coerceIn(0.0, 1.0).toFloat()
  if (approach > 0)
    drawLine(
      colors.content.copy(alpha = approach * 0.5f),
      Offset(edge, at.y - 0.035f),
      Offset(edge, at.y + 0.035f),
      0.006f,
    )
}
