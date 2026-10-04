package xyz.mcxross.formation.ricochet.ui

import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import xyz.mcxross.formation.design.tokens.Colors
import xyz.mcxross.formation.ricochet.Arena
import xyz.mcxross.formation.ricochet.ImpactKind
import xyz.mcxross.formation.ricochet.RicochetState

internal fun DrawScope.drawHalf(state: RicochetState, side: Int, now: Long, colors: Colors) {
  val height = Arena.HEIGHT.toFloat()
  val danger = dangerPulse(state, now)
  drawRect(if (danger > 0) colors.accent.copy(alpha = 0.15f + danger * 0.35f) else colors.line,
    Offset(0.002f, 0.002f), Size(0.996f, height - 0.004f), style = Stroke(0.002f))
  state.targets.filter { it.x.toInt() == side }.forEach { target ->
    val x = (target.x - side).toFloat()
    val y = target.y.toFloat()
    val w = Arena.TARGET_WIDTH.toFloat() / 2
    val h = Arena.TARGET_HEIGHT.toFloat() / 2
    val cut = 0.019f
    val tile = Path().apply {
      moveTo(x - w + cut, y - h); lineTo(x + w - cut, y - h)
      lineTo(x + w, y - h + cut); lineTo(x + w, y + h - cut)
      lineTo(x + w - cut, y + h); lineTo(x - w + cut, y + h)
      lineTo(x - w, y + h - cut); lineTo(x - w, y - h + cut); close()
    }
    drawPath(tile, colors.accent)
    val seam = Path().apply {
      moveTo(x - 0.02f, y - h); lineTo(x - 0.02f, y - 0.015f)
      lineTo(x + 0.02f, y + 0.015f); lineTo(x + 0.02f, y + h)
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
  drawRoundRect(colors.content, Offset(px - paddleWidth / 2, paddle.y.toFloat() - paddleHeight / 2),
    Size(paddleWidth, paddleHeight), CornerRadius(paddleWidth / 2))

  val pulse = presentedPulse(state, now)
  val at = Offset((pulse.x - side).toFloat(), pulse.y.toFloat())
  if (now >= state.serveAt || state.finishedAt != null) {
    val pace = ((state.momentum.factor - 1) / 0.5).toFloat()
    val tailSeconds = 0.10 + pace * 0.05
    val tail = Offset(at.x - (pulse.vx * tailSeconds).toFloat(), at.y - (pulse.vy * tailSeconds).toFloat())
    drawLine(colors.content.copy(alpha = 0.25f + pace * 0.25f), tail, at, 0.006f + pace * 0.004f, StrokeCap.Round)
    drawCircle(colors.content, Arena.RADIUS.toFloat(), at)
    val edge = if (side == 0) 0.995f else 0.005f
    val approach = (1 - kotlin.math.abs(pulse.x - 1.0) / 0.35).coerceIn(0.0, 1.0).toFloat()
    if (approach > 0) drawLine(colors.content.copy(alpha = approach * 0.5f), Offset(edge, at.y - 0.035f), Offset(edge, at.y + 0.035f), 0.006f)
  } else {
    val seconds = ((state.serveAt - now + 999) / 1_000).coerceIn(1, 2).toInt()
    val phase = ((now - state.serveAt + 1_200).coerceAtLeast(0) % 500) / 500f
    repeat(seconds) { drawCircle(colors.content.copy(alpha = 0.4f + phase * 0.3f), 0.014f, Offset(0.48f + it * 0.05f, height / 2)) }
  }
  drawImpacts(state, side, now, colors)
}

private fun DrawScope.drawImpacts(state: RicochetState, side: Int, now: Long, colors: Colors) {
  state.impacts.forEach { impact ->
    val age = (now - impact.at).coerceAtLeast(0)
    if (age >= 420) return@forEach
    val t = age / 420f
    val at = Offset((impact.x - side).toFloat(), impact.y.toFloat())
    when (impact.kind) {
      ImpactKind.Target -> repeat(8) { index ->
        val angle = index * kotlin.math.PI / 4 + impact.id * 0.37
        val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        val start = at + direction * (0.025f + t * 0.17f)
        drawLine((if (index % 3 == 0) colors.content else colors.accent).copy(alpha = 1 - t),
          start, start + direction * (0.026f * (1 - t)), 0.009f)
      }
      ImpactKind.Paddle -> {
        val emphasis = if (impact.grazed) 1.4f else 1f
        drawCircle(colors.content.copy(alpha = (1 - t) * 0.45f), 0.03f + t * 0.08f * emphasis, at, style = Stroke(0.003f))
      }
      ImpactKind.Miss -> drawRect(colors.accent.copy(alpha = (1 - t) * 0.12f), size = Size(1f, Arena.HEIGHT.toFloat()))
      ImpactKind.Wall -> {}
    }
  }
}
