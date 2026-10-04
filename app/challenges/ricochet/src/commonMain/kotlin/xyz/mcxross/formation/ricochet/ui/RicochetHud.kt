package xyz.mcxross.formation.ricochet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.ricochet.RicochetState

@Composable
internal fun RicochetHud(state: RicochetState, seconds: Int, now: State<Long>) {
  val colors = Theme.colors
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text("${state.clears} / 6", style = Theme.type.numeral, modifier = Modifier.semantics {
        contentDescription = "${state.clears} of 6 targets cleared"
      })
      Canvas(Modifier.size(58.dp, 18.dp).semantics {
        contentDescription = "${RicochetState.MAX_MISSES - state.misses} shared lives remaining"
      }) {
        repeat(3) { index ->
          val at = Offset(size.width * (index + 0.5f) / 3, center.y)
          if (index < 3 - state.misses) {
            val danger = dangerPulse(state, now.value)
            if (state.misses == 2) drawCircle(colors.accent.copy(alpha = 0.14f + danger * 0.25f),
              (6 + danger * 2).dp.toPx(), at)
            drawCircle(if (state.misses == 2) colors.accent else colors.content, 4.dp.toPx(), at)
          }
          else drawCircle(colors.lineStrong, 4.dp.toPx(), at, style = Stroke(1.dp.toPx()))
        }
      }
      Text("${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}",
        style = Theme.type.numeral, color = if (seconds <= 10) colors.negative else colors.content)
    }
    Canvas(Modifier.fillMaxWidth().height(3.dp)) {
      val gap = 4.dp.toPx()
      val width = (size.width - gap * 5) / 6
      repeat(6) { drawRect(if (it < state.clears) colors.accent else colors.line, Offset(it * (width + gap), 0f), Size(width, size.height)) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      MomentumReadout(state.momentum)
    }
  }
}
