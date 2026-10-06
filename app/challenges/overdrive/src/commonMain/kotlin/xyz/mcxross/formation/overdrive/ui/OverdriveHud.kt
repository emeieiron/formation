package xyz.mcxross.formation.overdrive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.foundation.Text

@Composable
internal fun OverdriveHud(state: OverdriveState, tenths: Int) {
  val colors = Theme.colors
  val glow = remember { Animatable(0f) }
  val beat = remember { Animatable(1f) }
  val urgent = tenths <= URGENT_TENTHS
  OnChange(state.clears) { _, _ ->
    glow.snapTo(1f)
    glow.animateTo(0f, tween(500))
  }
  OnChange((tenths + 9) / 10) { _, seconds ->
    if (seconds in 1..URGENT_TENTHS / 10) {
      beat.snapTo(1.22f)
      beat.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 600f))
    }
  }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween) {
      Text("${state.clears.toString().padStart(2, '0')} / 12", style = Theme.type.numeral)
      Text(clock(tenths), style = Theme.type.numeral, color = if (urgent) colors.negative else colors.content,
        modifier = Modifier.graphicsLayer {
          scaleX = beat.value
          scaleY = beat.value
        })
    }
    Canvas(Modifier.fillMaxWidth().height(5.dp).semantics {
      contentDescription = "${state.clears} of 12 waves complete"
    }) {
      val gap = 3.dp.toPx()
      val width = (size.width - gap * 11) / 12
      repeat(12) { index ->
        val at = Offset(index * (width + gap), 0f)
        drawRoundRect(if (index < state.clears) colors.accent else colors.line, at, Size(width, size.height),
          CornerRadius(1.dp.toPx()))
        if (index == state.clears - 1 && glow.value > 0f) {
          drawRoundRect(Color.White.copy(alpha = glow.value), at, Size(width, size.height), CornerRadius(1.dp.toPx()))
        }
      }
    }
  }
}

private fun clock(tenths: Int): String {
  if (tenths <= URGENT_TENTHS) return "${tenths / 10}.${tenths % 10}"
  val seconds = (tenths + 9) / 10
  return "${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}

internal const val URGENT_TENTHS = 100
