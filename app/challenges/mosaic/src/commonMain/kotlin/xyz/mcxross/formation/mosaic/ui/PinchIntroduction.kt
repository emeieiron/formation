package xyz.mcxross.formation.mosaic.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.delay
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.mosaic.Artwork

// Practice in the briefing: two pretend phones share the top bar. Sliding one finger on each toward
// the
// seam seals it. Nothing here reaches the Seeker.
@Composable
internal fun PinchIntroduction() {
  val colors = Theme.colors
  var sealedAt by remember { mutableLongStateOf(0L) }
  var sealed by remember { mutableStateOf(false) }
  val demo by
    rememberInfiniteTransition(label = "pinch demo")
      .animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(1_600, easing = LinearEasing)),
        label = "fingers",
      )
  if (sealed)
    LaunchedEffect(sealedAt) {
      delay(1_800)
      sealed = false
    }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Pinch across a seam", style = Theme.type.subheadStrong)
    Canvas(
      Modifier.fillMaxWidth()
        .height(150.dp)
        .semantics {
          contentDescription = "Practice sealing a seam between two phones."
          stateDescription = if (sealed) "Sealed" else "Open"
          onClick("Seal the seam") {
            sealed = true
            sealedAt++
            true
          }
        }
        .pointerInput(Unit) {
          val starts = mutableMapOf<PointerId, Offset>()
          val lifts = mutableListOf<Pair<Boolean, Long>>()
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              event.changes.forEach { change ->
                if (change.changedToDownIgnoreConsumed()) starts[change.id] = change.position
                if (change.changedToUpIgnoreConsumed()) {
                  val start = starts.remove(change.id)
                  val seam = size.width / 2f
                  val travel = start?.let { change.position.x - it.x } ?: 0f
                  val toward =
                    start != null &&
                      abs(travel) > 24.dp.toPx() &&
                      abs(change.position.x - seam) < size.width * 0.18f &&
                      (start.x < seam) == (travel > 0)
                  if (toward) {
                    val left = start.x < seam
                    lifts.removeAll { change.uptimeMillis - it.second > 300 }
                    if (lifts.any { it.first != left }) {
                      sealed = true
                      sealedAt = change.uptimeMillis
                      lifts.clear()
                    } else lifts += left to change.uptimeMillis
                  }
                }
                change.consume()
              }
            }
          }
        }
    ) {
      val gap = 10.dp.toPx()
      val phoneWidth = (size.width - gap) / 2
      val left = Rect(0f, 0f, phoneWidth, size.height)
      val right = Rect(phoneWidth + gap, 0f, size.width, size.height)
      // The top bar fills both screens, with the bezel hiding the canvas between them.
      val scale = (size.width / (Artwork.WIDTH.toFloat() + 6f))
      val bar = Artwork.bars.first()
      val top = (size.height - (bar.endInclusive - bar.start).toFloat() * scale) / 2
      listOf(left, right).forEach { screen ->
        drawRoundRect(Color.Black, screen.topLeft, screen.size, CornerRadius(8.dp.toPx()))
        clipRect(
          screen.left + 4.dp.toPx(),
          screen.top + 4.dp.toPx(),
          screen.right - 4.dp.toPx(),
          screen.bottom - 4.dp.toPx(),
        ) {
          translate(3f * scale, top) {
            scale(scale, scale, Offset.Zero) { drawPath(Mark.path, Mark.brush) }
          }
        }
      }
      val seamTop = Offset(size.width / 2, 8.dp.toPx())
      val seamBottom = Offset(size.width / 2, size.height - 8.dp.toPx())
      if (sealed) {
        drawLine(
          colors.accent.copy(alpha = 0.3f),
          seamTop,
          seamBottom,
          16.dp.toPx(),
          StrokeCap.Round,
        )
        drawLine(colors.accent, seamTop, seamBottom, 4.dp.toPx(), StrokeCap.Round)
      } else {
        // Two fingers sliding together show the gesture until someone tries it.
        val reach = phoneWidth * 0.45f
        val y = size.height * 0.78f
        val travel = reach * demo
        val fade = 1f - demo
        drawCircle(
          Color.White.copy(alpha = 0.55f * fade + 0.15f),
          11.dp.toPx(),
          Offset(size.width / 2 - reach + travel - gap, y),
        )
        drawCircle(
          Color.White.copy(alpha = 0.55f * fade + 0.15f),
          11.dp.toPx(),
          Offset(size.width / 2 + reach - travel + gap, y),
        )
      }
    }
    Text(
      if (sealed) "Sealed. Every seam needs one pinch."
      else "One finger on each phone, then slide them together.",
      style = Theme.type.caption,
      color = colors.contentSecondary,
    )
  }
}
