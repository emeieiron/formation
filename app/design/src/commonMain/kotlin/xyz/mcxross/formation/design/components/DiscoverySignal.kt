package xyz.mcxross.formation.design.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.design.Theme

/** An indeterminate search signal; its adjacent text supplies the accessible status. */
@Composable
fun DiscoverySignal(modifier: Modifier = Modifier) {
  val c = Theme.colors
  val phase =
    rememberInfiniteTransition(label = "discovery")
      .animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing)),
        label = "signal-travel",
      )
  Canvas(modifier.size(48.dp, 24.dp).clearAndSetSemantics {}) {
    val track = size.width * 2 / 3
    val packet = 10.dp.toPx()
    val thickness = 2.dp.toPx()
    val cut = thickness
    repeat(3) { lane ->
      val start = lane * size.width / 6
      val y = size.height * (lane + 0.5f) / 3
      drawLine(c.lineStrong, Offset(start, y), Offset(start + track, y), thickness)

      val travel = (phase.value + lane / 3f) % 1f
      // Fade at the ends so the loop resets without a visible jump.
      val opacity = minOf(travel / 0.12f, (1 - travel) / 0.12f).coerceIn(0f, 1f)
      val x = start + (track - packet) * travel
      val top = y - thickness / 2
      val bottom = y + thickness / 2
      drawPath(
        Path().apply {
          moveTo(x + cut, top)
          lineTo(x + packet, top)
          lineTo(x + packet - cut, bottom)
          lineTo(x, bottom)
          close()
        },
        c.accent.copy(alpha = opacity),
      )
      drawLine(
        c.content.copy(alpha = opacity),
        Offset(x + packet - cut, bottom),
        Offset(x + packet, top),
        strokeWidth = 1.dp.toPx(),
      )
    }
  }
}
