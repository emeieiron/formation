package xyz.mcxross.formation.ricochet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.ricochet.Momentum

@Composable
internal fun MomentumReadout(momentum: Momentum) {
  val colors = Theme.colors
  val percent = (momentum.factor * 100).roundToInt()
  Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.semantics(mergeDescendants = true) {
        contentDescription = "Pulse speed $percent percent"
      },
  ) {
    Canvas(Modifier.size(48.dp, 12.dp)) {
      val cell = size.width / 6
      repeat(6) { index ->
        drawLine(
          if (index < momentum.exchanges) colors.accent else colors.lineStrong,
          Offset(cell * index + cell * 0.2f, size.height * 0.8f),
          Offset(cell * index + cell * 0.7f, size.height * 0.2f),
          2.dp.toPx(),
          StrokeCap.Round,
        )
      }
    }
    Text(
      "${percent / 100}.${(percent % 100).toString().padStart(2, '0')}×",
      style = Theme.type.overline,
      color = if (percent > 100) colors.content else colors.contentTertiary,
    )
  }
}
