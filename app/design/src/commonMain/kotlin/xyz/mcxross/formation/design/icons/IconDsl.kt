package xyz.mcxross.formation.design.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// 24-unit grid, round 1.8-unit strokes; tinted where used.
internal const val STROKE = 1.8f

private val Ink = SolidColor(Color.Black)

internal class IconBuilder(name: String) {
  private val builder =
    ImageVector.Builder(
      name = name,
      defaultWidth = 24.dp,
      defaultHeight = 24.dp,
      viewportWidth = 24f,
      viewportHeight = 24f,
    )

  fun stroke(width: Float = STROKE, alpha: Float = 1f, block: PathBuilder.() -> Unit) {
    builder.path(
      stroke = Ink,
      strokeAlpha = alpha,
      strokeLineWidth = width,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
      pathBuilder = block,
    )
  }

  fun fill(evenOdd: Boolean = false, alpha: Float = 1f, block: PathBuilder.() -> Unit) {
    builder.path(
      fill = Ink,
      fillAlpha = alpha,
      pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
      pathBuilder = block,
    )
  }

  fun build(): ImageVector = builder.build()
}

internal fun icon(name: String, block: IconBuilder.() -> Unit): ImageVector =
  IconBuilder(name).apply(block).build()

internal fun PathBuilder.line(x1: Float, y1: Float, x2: Float, y2: Float) {
  moveTo(x1, y1)
  lineTo(x2, y2)
}

internal fun PathBuilder.polyline(vararg p: Float) {
  moveTo(p[0], p[1])
  var i = 2
  while (i < p.size) {
    lineTo(p[i], p[i + 1])
    i += 2
  }
}

internal fun PathBuilder.polygon(vararg p: Float) {
  polyline(*p)
  close()
}

internal fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
  moveTo(cx - r, cy)
  arcTo(r, r, 0f, false, true, cx + r, cy)
  arcTo(r, r, 0f, false, true, cx - r, cy)
  close()
}

internal fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
  moveTo(l + rad, t)
  lineTo(r - rad, t)
  arcTo(rad, rad, 0f, false, true, r, t + rad)
  lineTo(r, b - rad)
  arcTo(rad, rad, 0f, false, true, r - rad, b)
  lineTo(l + rad, b)
  arcTo(rad, rad, 0f, false, true, l, b - rad)
  lineTo(l, t + rad)
  arcTo(rad, rad, 0f, false, true, l + rad, t)
  close()
}

/** Screen angles: 0° points right, 90° points down, positive sweeps run clockwise. */
internal fun PathBuilder.arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float) {
  moveTo(cx + r * cosDeg(startDeg), cy + r * sinDeg(startDeg))
  val end = startDeg + sweepDeg
  arcTo(r, r, 0f, abs(sweepDeg) > 180f, sweepDeg > 0f, cx + r * cosDeg(end), cy + r * sinDeg(end))
}

internal fun PathBuilder.arrowHead(x: Float, y: Float, headingDeg: Float, size: Float = 3.4f) {
  val back = headingDeg + 180f
  polyline(
    x + size * cosDeg(back - 40f),
    y + size * sinDeg(back - 40f),
    x,
    y,
    x + size * cosDeg(back + 40f),
    y + size * sinDeg(back + 40f),
  )
}

internal fun PathBuilder.star(cx: Float, cy: Float, outer: Float, inner: Float, points: Int = 4) {
  for (i in 0 until points * 2) {
    val a = -90f + i * 180f / points
    val r = if (i % 2 == 0) outer else inner
    val x = cx + r * cosDeg(a)
    val y = cy + r * sinDeg(a)
    if (i == 0) moveTo(x, y) else lineTo(x, y)
  }
  close()
}

internal fun PathBuilder.phone(
  cx: Float,
  cy: Float,
  w: Float,
  h: Float,
  deg: Float = 0f,
  r: Float = 1.8f,
) {
  if (deg == 0f) {
    roundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, r)
    return
  }
  val corners = listOf(-w / 2 to -h / 2, w / 2 to -h / 2, w / 2 to h / 2, -w / 2 to h / 2)
  corners.forEachIndexed { i, (x, y) ->
    val px = cx + x * cosDeg(deg) - y * sinDeg(deg)
    val py = cy + x * sinDeg(deg) + y * cosDeg(deg)
    if (i == 0) moveTo(px, py) else lineTo(px, py)
  }
  close()
}

internal fun cosDeg(d: Float) = cos(d * PI / 180).toFloat()

internal fun sinDeg(d: Float) = sin(d * PI / 180).toFloat()
