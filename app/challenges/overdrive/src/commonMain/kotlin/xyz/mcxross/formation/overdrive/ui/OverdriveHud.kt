package xyz.mcxross.formation.overdrive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.foundation.Text

@Composable
internal fun OverdriveHud(state: OverdriveState, seconds: Int) {
  val colors = Theme.colors
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween) {
      Text("${state.clears.toString().padStart(2, '0')} / 12", style = Theme.type.numeral)
      Canvas(Modifier.size(58.dp, 18.dp).semantics {
        contentDescription = "${OverdriveState.MAX_MISSES - state.misses} shared misses remaining"
      }) {
        repeat(3) { index ->
          val at = Offset(size.width * (index + 0.5f) / 3f, center.y)
          if (index < OverdriveState.MAX_MISSES - state.misses) drawCircle(colors.content, 4.dp.toPx(), at)
          else drawCircle(colors.lineStrong, 4.dp.toPx(), at, style = Stroke(1.dp.toPx()))
        }
      }
      Text("${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}", style = Theme.type.numeral)
    }
    Canvas(Modifier.fillMaxWidth().height(5.dp).semantics {
      contentDescription = "${state.clears} of 12 waves complete"
    }) {
      val gap = 3.dp.toPx()
      val width = (size.width - gap * 11) / 12
      repeat(12) { index ->
        drawRoundRect(if (index < state.clears) colors.accent else colors.line,
          Offset(index * (width + gap), 0f), Size(width, size.height), CornerRadius(1.dp.toPx()))
      }
    }
  }
}
