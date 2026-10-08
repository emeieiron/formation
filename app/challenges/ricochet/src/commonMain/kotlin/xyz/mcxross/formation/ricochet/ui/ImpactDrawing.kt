package xyz.mcxross.formation.ricochet.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin
import xyz.mcxross.formation.design.tokens.Colors
import xyz.mcxross.formation.ricochet.Arena
import xyz.mcxross.formation.ricochet.ImpactKind
import xyz.mcxross.formation.ricochet.RicochetState

internal fun DrawScope.drawImpacts(state: RicochetState, side: Int, now: Long, colors: Colors) {
  state.impacts.forEach { impact ->
    val age = (now - impact.at).coerceAtLeast(0)
    if (age >= 420) return@forEach
    val t = age / 420f
    val at = Offset((impact.x - side).toFloat(), impact.y.toFloat())
    when (impact.kind) {
      ImpactKind.Target,
      ImpactKind.Pierce -> {
        repeat(8) { index ->
          val angle = index * kotlin.math.PI / 4 + impact.id * 0.37
          val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
          val start = at + direction * (0.025f + t * 0.17f)
          drawLine(
            (if (index % 3 == 0) colors.content else colors.accent).copy(alpha = 1 - t),
            start,
            start + direction * (0.026f * (1 - t)),
            0.009f,
          )
        }
        if (impact.kind == ImpactKind.Pierce)
          drawLine(
            colors.content.copy(alpha = 1 - t),
            at - Offset(0.10f + t * 0.12f, 0f),
            at + Offset(0.10f + t * 0.12f, 0f),
            0.007f * (1 - t),
          )
      }
      ImpactKind.Paddle -> {
        val emphasis = if (impact.grazed) 1.4f else 1f
        drawCircle(
          colors.content.copy(alpha = (1 - t) * 0.45f),
          0.03f + t * 0.08f * emphasis,
          at,
          style = Stroke(0.003f),
        )
      }
      ImpactKind.Charge ->
        drawCircle(
          colors.accent.copy(alpha = (1 - t) * 0.8f),
          0.04f + t * 0.18f,
          at,
          style = Stroke(0.004f),
        )
      ImpactKind.Miss ->
        drawRect(
          colors.accent.copy(alpha = (1 - t) * 0.12f),
          size = Size(1f, Arena.HEIGHT.toFloat()),
        )
      ImpactKind.Wall -> {}
    }
  }
}
