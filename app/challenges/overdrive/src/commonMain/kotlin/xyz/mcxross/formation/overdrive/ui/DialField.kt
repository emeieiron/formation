package xyz.mcxross.formation.overdrive

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun DialField(dial: Dial, wave: Int, turns: Int, now: State<Long>,
  onRotate: () -> Unit, modifier: Modifier = Modifier) {
  val handler by rememberUpdatedState(onRotate)
  val rotation = animateFloatAsState(turns * 90f, tween(130, easing = FastOutSlowInEasing), label = "dial")
  Canvas(modifier.pointerInput(Unit) { detectTapGestures(onPress = { handler() }) }
    .semantics {
      role = Role.Button
      contentDescription = "Rotate clockwise"
      stateDescription = "Wave $wave. Top: ${dial.facing(turns).label}. Turns: $turns. Edges: ${dial.edges.joinToString { it.label }}"
      onClick("Rotate clockwise") { handler(); true }
    }) {
    val half = minOf(size.width * 0.39f, size.height * 0.33f)
    val at = Offset(center.x, size.height * 0.64f)
    val inner = half * 0.54f
    val chamfer = half * 0.17f
    val plate = Path().apply {
      moveTo(at.x - half + chamfer, at.y - half)
      lineTo(at.x + half - chamfer, at.y - half)
      lineTo(at.x + half, at.y - half + chamfer)
      lineTo(at.x + inner, at.y - inner)
      lineTo(at.x - inner, at.y - inner)
      lineTo(at.x - half, at.y - half + chamfer)
      close()
    }
    dial.edges.forEachIndexed { index, symbol ->
      val angle = rotation.value + index * 90f
      rotate(angle, at) { drawPath(plate, symbol.color) }
      val radians = angle * PI.toFloat() / 180f
      val radius = (half + inner) * 0.5f
      val glyph = at + Offset(sin(radians) * radius, -cos(radians) * radius)
      drawSymbol(symbol, glyph, half * 0.12f, Color.White)
    }

    val time = now.value
    val startY = 16.dp.toPx()
    val hitY = at.y - half - 8.dp.toPx()
    val fraction = ((time - dial.launchAt).toFloat() / (dial.catchAt - dial.launchAt)).coerceIn(0f, 1f)
    if (time in dial.launchAt until dial.catchAt) {
      val pulse = Offset(at.x, startY + (hitY - startY) * fraction)
      repeat(5) { index ->
        drawCircle(Color.White.copy(alpha = 0.10f * (5 - index)),
          (5 - index).dp.toPx(), pulse - Offset(0f, (index + 1) * 5.dp.toPx()))
      }
      drawCircle(Color.White, 7.dp.toPx(), pulse)
    }
    val elapsed = time - dial.catchAt
    if (dial.result != Catch.Pending && elapsed in 0..450) {
      val progress = elapsed / 450f
      val color = if (dial.result == Catch.Caught) Color.White else Symbol.Triangle.color
      val radius = 8.dp.toPx() + progress * 28.dp.toPx()
      drawCircle(color.copy(alpha = 1f - progress), radius, Offset(at.x, hitY), style = Stroke(2.dp.toPx()))
      repeat(4) { index ->
        val angle = index * PI.toFloat() / 2f
        drawCircle(color.copy(alpha = 1f - progress), 2.dp.toPx(),
          Offset(at.x, hitY) + Offset(cos(angle), sin(angle)) * radius)
      }
    }
  }
}
