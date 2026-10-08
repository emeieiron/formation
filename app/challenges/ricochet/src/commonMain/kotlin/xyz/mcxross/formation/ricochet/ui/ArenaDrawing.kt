package xyz.mcxross.formation.ricochet.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import xyz.mcxross.formation.design.tokens.Colors
import xyz.mcxross.formation.ricochet.Arena
import xyz.mcxross.formation.ricochet.ImpactKind
import xyz.mcxross.formation.ricochet.RicochetState

internal fun DrawScope.drawHalf(state: RicochetState, side: Int, now: Long, colors: Colors) {
  val height = Arena.HEIGHT.toFloat()
  val danger = dangerPulse(state, now)
  drawRect(
    if (danger > 0) colors.accent.copy(alpha = 0.15f + danger * 0.35f) else colors.line,
    Offset(0.002f, 0.002f),
    Size(0.996f, height - 0.004f),
    style = Stroke(0.002f),
  )
  state.targets
    .filter { it.x.toInt() == side }
    .forEach { target ->
      val x = (target.x - side).toFloat()
      val y = target.y.toFloat()
      val w = Arena.TARGET_WIDTH.toFloat() / 2
      val h = Arena.TARGET_HEIGHT.toFloat() / 2
      val cut = 0.019f
      val tile =
        Path().apply {
          moveTo(x - w + cut, y - h)
          lineTo(x + w - cut, y - h)
          lineTo(x + w, y - h + cut)
          lineTo(x + w, y + h - cut)
          lineTo(x + w - cut, y + h)
          lineTo(x - w + cut, y + h)
          lineTo(x - w, y + h - cut)
          lineTo(x - w, y - h + cut)
          close()
        }
      drawPath(tile, colors.accent)
      val seam =
        Path().apply {
          moveTo(x - 0.02f, y - h)
          lineTo(x - 0.02f, y - 0.015f)
          lineTo(x + 0.02f, y + 0.015f)
          lineTo(x + 0.02f, y + h)
        }
      drawPath(seam, colors.background.copy(alpha = 0.65f), style = Stroke(0.0025f))
    }
  val paddle = state.paddles.first { it.side == side }
  val contact = state.impacts.lastOrNull { it.kind == ImpactKind.Paddle && it.side == side }
  val punch = contact?.let { (1f - (now - it.at).coerceAtLeast(0) / 220f).coerceAtLeast(0f) } ?: 0f
  val intensity = if (contact?.grazed == true) 1.4f else 1f
  val paddleHeight = state.paddleHeight.toFloat() * (1 - punch * 0.12f * intensity)
  val paddleWidth = Arena.PADDLE_WIDTH.toFloat() * (1 + punch * 0.4f * intensity)
  val px = (Arena.paddleX(side) - side).toFloat()
  drawRoundRect(
    colors.content,
    Offset(px - paddleWidth / 2, paddle.y.toFloat() - paddleHeight / 2),
    Size(paddleWidth, paddleHeight),
    CornerRadius(paddleWidth / 2),
  )

  drawPulse(state, side, now, colors)
  drawImpacts(state, side, now, colors)
}
