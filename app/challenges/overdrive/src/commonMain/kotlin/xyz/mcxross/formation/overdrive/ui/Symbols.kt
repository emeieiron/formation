package xyz.mcxross.formation.overdrive

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

internal val Symbol.color: Color
  get() =
    when (this) {
      Symbol.Triangle -> Color(0xFFE52629)
      Symbol.Circle -> Color(0xFF56C5F6)
      Symbol.Cross -> Color(0xFFFFB547)
      Symbol.Diamond -> Color(0xFF9B7BFF)
    }

@Composable
internal fun SymbolGlyph(symbol: Symbol, modifier: Modifier = Modifier) {
  Canvas(modifier.semantics { contentDescription = symbol.label }) {
    drawSymbol(symbol, center, size.minDimension * 0.45f, symbol.color)
  }
}

internal fun DrawScope.drawSymbol(symbol: Symbol, at: Offset, radius: Float, color: Color) =
  with(SymbolShapes(radius)) { draw(symbol, at, color) }

internal class SymbolShapes(private val radius: Float) {
  private val triangle =
    outline(
      listOf(Offset(0f, -radius), Offset(radius, radius * 0.8f), Offset(-radius, radius * 0.8f))
    )
  private val diamond =
    outline(
      listOf(Offset(0f, -radius), Offset(radius, 0f), Offset(0f, radius), Offset(-radius, 0f))
    )

  fun DrawScope.draw(symbol: Symbol, at: Offset, color: Color) {
    when (symbol) {
      Symbol.Circle -> drawCircle(color, radius, at)
      Symbol.Cross -> {
        val inset = radius * 0.7f
        drawLine(
          color,
          at + Offset(-inset, -inset),
          at + Offset(inset, inset),
          radius * 0.45f,
          StrokeCap.Square,
        )
        drawLine(
          color,
          at + Offset(-inset, inset),
          at + Offset(inset, -inset),
          radius * 0.45f,
          StrokeCap.Square,
        )
      }
      Symbol.Triangle -> translate(at.x, at.y) { drawPath(triangle, color) }
      Symbol.Diamond -> translate(at.x, at.y) { drawPath(diamond, color) }
    }
  }

  private fun outline(points: List<Offset>) =
    Path().apply {
      moveTo(points.first().x, points.first().y)
      points.drop(1).forEach { lineTo(it.x, it.y) }
      close()
    }
}
