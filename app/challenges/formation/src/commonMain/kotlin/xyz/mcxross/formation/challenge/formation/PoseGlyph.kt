package xyz.mcxross.formation.challenge.formation

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import xyz.mcxross.formation.sensors.Pose

@Composable
internal fun PoseGlyph(
  pose: Pose,
  color: Color,
  modifier: Modifier,
  ink: Color = Color(0xFF06070A),
) {
  Canvas(modifier) {
    val w = size.minDimension
    when (pose) {
      Pose.UPRIGHT -> standing(0f, color, ink, w)
      Pose.UPSIDE_DOWN -> standing(180f, color, ink, w)
      Pose.SIDEWAYS_LEFT -> standing(-90f, color, ink, w)
      Pose.SIDEWAYS_RIGHT -> standing(90f, color, ink, w)
      Pose.FACE_UP -> flat(screenUp = true, color, ink, w)
      Pose.FACE_DOWN -> flat(screenUp = false, color, ink, w)
      Pose.TILTED -> drawCircle(color.copy(alpha = 0.4f), w * 0.2f, style = Stroke(w * 0.04f))
    }
  }
}

private fun DrawScope.standing(deg: Float, color: Color, ink: Color, w: Float) {
  rotate(deg) {
    val pw = w * 0.36f
    val ph = w * 0.62f
    val tl = Offset(center.x - pw / 2, center.y - ph / 2 + w * 0.06f)
    drawRoundRect(color, tl, Size(pw, ph), CornerRadius(pw * 0.2f))
    drawRoundRect(
      ink.copy(alpha = 0.85f),
      tl + Offset(pw * 0.1f, ph * 0.1f),
      Size(pw * 0.8f, ph * 0.8f),
      CornerRadius(pw * 0.1f),
    )
    drawRoundRect(
      color,
      Offset(center.x - pw * 0.16f, tl.y + ph * 0.035f),
      Size(pw * 0.32f, ph * 0.035f),
      CornerRadius(ph * 0.02f),
    )
    val arrow =
      Path().apply {
        moveTo(center.x, tl.y - w * 0.16f)
        lineTo(center.x + w * 0.1f, tl.y - w * 0.05f)
        lineTo(center.x - w * 0.1f, tl.y - w * 0.05f)
        close()
      }
    drawPath(arrow, color)
  }
}

private fun DrawScope.flat(screenUp: Boolean, color: Color, ink: Color, w: Float) {
  val top = w * 0.3f
  val bottom = w * 0.72f
  val inset = w * 0.12f
  val body =
    Path().apply {
      moveTo(w * 0.3f, top)
      lineTo(w * 0.7f, top)
      lineTo(w * 0.86f, bottom)
      lineTo(w * 0.14f, bottom)
      close()
    }
  drawPath(body, color)
  drawLine(
    color.copy(alpha = 0.5f),
    Offset(w * 0.14f, bottom + w * 0.03f),
    Offset(w * 0.86f, bottom + w * 0.03f),
    w * 0.02f,
  )
  if (screenUp) {
    val screen =
      Path().apply {
        moveTo(w * 0.32f + inset * 0.2f, top + w * 0.03f)
        lineTo(w * 0.68f - inset * 0.2f, top + w * 0.03f)
        lineTo(w * 0.82f - inset * 0.2f, bottom - w * 0.03f)
        lineTo(w * 0.18f + inset * 0.2f, bottom - w * 0.03f)
        close()
      }
    drawPath(screen, Color.White.copy(alpha = 0.75f))
  } else {
    drawCircle(ink.copy(alpha = 0.7f), w * 0.045f, Offset(w * 0.4f, top + w * 0.09f))
    drawCircle(ink.copy(alpha = 0.7f), w * 0.045f, Offset(w * 0.5f, top + w * 0.09f))
  }
}

internal val Pose.instruction: String
  get() =
    when (this) {
      Pose.UPRIGHT -> "Stand it upright"
      Pose.UPSIDE_DOWN -> "Stand it upside down"
      Pose.SIDEWAYS_LEFT -> "On its side, top to the left"
      Pose.SIDEWAYS_RIGHT -> "On its side, top to the right"
      Pose.FACE_UP -> "Flat, screen up"
      Pose.FACE_DOWN -> "Flat, screen down"
      Pose.TILTED -> "Anyhow"
    }
