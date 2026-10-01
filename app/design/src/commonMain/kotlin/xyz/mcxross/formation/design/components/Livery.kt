package xyz.mcxross.formation.design.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.tokens.Shapes

/** A decorative identity rule, never a progress indicator. */
@Composable
fun LiveryRule(modifier: Modifier = Modifier) {
  val c = Theme.colors
  Canvas(modifier.fillMaxWidth().height(3.dp)) {
    val split = size.width * 0.73f
    drawLine(c.accent, Offset.Zero, Offset(split, 0f), size.height * 2)
    val start = size.width * 0.76f
    val end = size.width * 0.93f
    drawPath(Path().apply {
      moveTo(start + size.height, 0f)
      lineTo(end + size.height, 0f)
      lineTo(end, size.height)
      lineTo(start, size.height)
      close()
    }, c.content)
  }
}

fun Modifier.liveryCard(): Modifier = composed {
  val c = Theme.colors
  clip(Shapes.card).drawWithContent {
    drawContent()
    drawLine(c.accent, Offset.Zero, Offset(size.width, 0f), 4.dp.toPx())
    val corner = 24.dp.toPx()
    drawPath(Path().apply {
      moveTo(size.width - corner, 0f)
      lineTo(size.width, 0f)
      lineTo(size.width, corner)
      close()
    }, c.content)
  }
}
