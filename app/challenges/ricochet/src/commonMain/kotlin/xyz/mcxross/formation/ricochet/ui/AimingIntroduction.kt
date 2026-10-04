package xyz.mcxross.formation.ricochet.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.ricochet.Arena

@Composable
internal fun AimingIntroduction() {
  val colors = Theme.colors
  var y by remember { mutableDoubleStateOf(AimingModel.CONTACT_Y) }
  val model = remember(y) { AimingModel(y) }
  val phase = rememberInfiniteTransition(label = "aiming").animateFloat(0f, 1f,
    infiniteRepeatable(tween(1_900, easing = LinearEasing)), label = "demonstration pulse")
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Drag to aim", style = Theme.type.subheadStrong)
    Canvas(Modifier.fillMaxWidth().height(188.dp).semantics {
      contentDescription = "Practice aiming. Move the paddle to change the dotted return."
      progressBarRangeInfo = ProgressBarRangeInfo(y.toFloat(), AimingModel.range.start.toFloat()..AimingModel.range.endInclusive.toFloat())
      setProgress { y = it.toDouble().coerceIn(AimingModel.range); true }
    }.pointerInput(Unit) {
      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        fun move(screenY: Float) {
          val factor = minOf(size.width.toFloat(), size.height / 0.6f)
          val origin = (size.height - factor * 0.6f) / 2
          y = ((screenY - origin) / factor).toDouble().coerceIn(AimingModel.range)
        }
        move(down.position.y)
        down.consume()
        do {
          val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
          move(change.position.y)
          change.consume()
        } while (change.pressed)
      }
    }) {
      val factor = minOf(size.width, size.height / 0.6f)
      translate((size.width - factor) / 2, (size.height - factor * 0.6f) / 2) {
        scale(factor, factor, Offset.Zero) {
          clipRect(0f, 0f, 1f, 0.6f) {
            drawRect(colors.line, size = Size(1f, 0.6f), style = Stroke(0.002f))
            drawRect(colors.content, Offset(Arena.PADDLE_X.toFloat() - 0.012f, y.toFloat() - 0.11f), Size(0.024f, 0.22f))
            drawRect(colors.accent.copy(alpha = if (model.hitsTarget) 1f else 0.45f),
              Offset((model.target.x - Arena.TARGET_WIDTH / 2).toFloat(), (model.target.y - Arena.TARGET_HEIGHT / 2).toFloat()),
              Size(Arena.TARGET_WIDTH.toFloat(), Arena.TARGET_HEIGHT.toFloat()))
            val start = Offset(model.outgoing.x.toFloat(), model.outgoing.y.toFloat())
            drawLine(if (model.hitsTarget) colors.accent else colors.contentSecondary, start,
              Offset(model.end.x.toFloat(), model.end.y.toFloat()), 0.004f,
              pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.012f, 0.018f)))
            val pulse = model.sample(phase.value)
            drawCircle(colors.content, Arena.RADIUS.toFloat(), Offset(pulse.x.toFloat(), pulse.y.toFloat()))
          }
        }
      }
    }
    Text("Two exchanges charge a piercing return.", style = Theme.type.caption, color = colors.contentSecondary)
  }
}
