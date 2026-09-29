package xyz.mcxross.formation.challenge.circuit

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal val SymbolNames = listOf("Circle", "Triangle", "Square", "Diamond", "Star", "Hexagon")

@Composable
internal fun SymbolGlyph(symbol: Int, color: Color, modifier: Modifier) {
  Canvas(modifier) {
    val r = size.minDimension / 2 * 0.86f
    val c = Offset(size.width / 2, size.height / 2)
    when (symbol) {
      0 -> drawCircle(color, r * 0.9f, c)
      1 -> drawPath(polygon(c, r, 3, -90.0), color)
      2 -> drawRect(color, Offset(c.x - r * 0.78f, c.y - r * 0.78f), Size(r * 1.56f, r * 1.56f))
      3 -> drawPath(polygon(c, r, 4, -90.0), color)
      4 -> drawPath(star(c, r, r * 0.45f, 5), color)
      else -> drawPath(polygon(c, r, 6, -90.0), color)
    }
  }
}

private fun polygon(c: Offset, r: Float, sides: Int, start: Double) =
  Path().apply {
    for (i in 0 until sides) {
      val a = (start + i * 360.0 / sides) * PI / 180
      val x = c.x + r * cos(a).toFloat()
      val y = c.y + r * sin(a).toFloat()
      if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
  }

private fun star(c: Offset, outer: Float, inner: Float, points: Int) =
  Path().apply {
    for (i in 0 until points * 2) {
      val a = (-90.0 + i * 180.0 / points) * PI / 180
      val r = if (i % 2 == 0) outer else inner
      val x = c.x + r * cos(a).toFloat()
      val y = c.y + r * sin(a).toFloat()
      if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
  }
