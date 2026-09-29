package xyz.mcxross.formation.design.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import xyz.mcxross.formation.design.Theme

class QrMatrix(val size: Int, private val dark: BooleanArray) {
  operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]
}

expect fun encodeQr(text: String): QrMatrix?

@Composable
fun QrCode(
  text: String,
  modifier: Modifier = Modifier,
  ink: Brush? = null,
  color: Color = Theme.colors.content,
) {
  val matrix = remember(text) { encodeQr(text) }
  Canvas(modifier) {
    val m = matrix ?: return@Canvas
    val cell = size.minDimension / m.size
    val brush = ink ?: Brush.linearGradient(listOf(color, color))
    fun finder(x: Int, y: Int) =
      (x < 7 && y < 7) || (x >= m.size - 7 && y < 7) || (x < 7 && y >= m.size - 7)
    for (y in 0 until m.size) for (x in 0 until m.size) {
      if (!m[x, y] || finder(x, y)) continue
      drawCircle(brush, cell * 0.43f, Offset((x + 0.5f) * cell, (y + 0.5f) * cell))
    }
    for ((fx, fy) in listOf(0 to 0, m.size - 7 to 0, 0 to m.size - 7)) {
      val stroke = cell
      drawRoundRect(
        brush,
        Offset(fx * cell + stroke / 2, fy * cell + stroke / 2),
        Size(7 * cell - stroke, 7 * cell - stroke),
        CornerRadius(cell * 2.2f),
        style = Stroke(stroke),
      )
      drawRoundRect(
        brush,
        Offset((fx + 2) * cell, (fy + 2) * cell),
        Size(3 * cell, 3 * cell),
        CornerRadius(cell * 1.1f),
      )
    }
  }
}
