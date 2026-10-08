package xyz.mcxross.formation.overdrive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.changedToDown
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
import xyz.mcxross.formation.design.effects.ParticleLayer

@Composable
internal fun DialField(
  dial: Dial,
  wave: Int,
  turns: Int,
  now: State<Long>,
  fx: StageFx,
  onRotate: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val handler by rememberUpdatedState(onRotate)
  // A stiff spring keeps its momentum when quick taps retarget it, so the dial never lags behind.
  val rotation =
    animateFloatAsState(
      turns * 90f,
      spring(dampingRatio = 0.75f, stiffness = 2_200f),
      label = "dial",
    )
  Box(modifier) {
    // Every finger that lands is a turn, so players can drum with two fingers.
    Spacer(
      Modifier.fillMaxSize()
        .pointerInput(Unit) {
          awaitPointerEventScope {
            while (true) {
              awaitPointerEvent().changes.forEach { change ->
                if (change.changedToDown()) {
                  change.consume()
                  handler()
                }
              }
            }
          }
        }
        .semantics {
          role = Role.Button
          contentDescription =
            "Rotate clockwise. Top: ${dial.facing(turns).label}. Clockwise edges: ${dial.edges.joinToString { it.label }}"
          stateDescription = "Wave $wave"
          onClick("Rotate clockwise") {
            handler()
            true
          }
        }
        .drawWithCache {
          val half = minOf(size.width * 0.39f, size.height * 0.33f)
          val at = Offset(size.width / 2f, size.height * 0.64f)
          val inner = half * 0.54f
          val chamfer = half * 0.17f
          val plate =
            Path().apply {
              moveTo(at.x - half + chamfer, at.y - half)
              lineTo(at.x + half - chamfer, at.y - half)
              lineTo(at.x + half, at.y - half + chamfer)
              lineTo(at.x + inner, at.y - inner)
              lineTo(at.x - inner, at.y - inner)
              lineTo(at.x - half, at.y - half + chamfer)
              close()
            }
          val shapes = SymbolShapes(half * 0.12f)
          val glyphRadius = (half + inner) * 0.5f
          val startY = 16.dp.toPx()
          val hitY = at.y - half - 8.dp.toPx()
          fx.hit = Offset(at.x, hitY)
          fx.core = at
          onDrawBehind {
            translate(fx.wobble.value.dp.toPx(), 0f) {
              scale(1f + 0.07f * fx.punch.value, pivot = at) {
                dial.edges.forEachIndexed { index, symbol ->
                  val angle = rotation.value + index * 90f
                  rotate(angle, at) { drawPath(plate, symbol.color) }
                  val radians = angle * PI.toFloat() / 180f
                  with(shapes) {
                    draw(
                      symbol,
                      at + Offset(sin(radians) * glyphRadius, -cos(radians) * glyphRadius),
                      Color.White,
                    )
                  }
                }
                if (fx.flash.value > 0f)
                  drawPath(plate, Color.White.copy(alpha = 0.7f * fx.flash.value))
              }
            }

            val time = now.value
            if (time in dial.launchAt until dial.catchAt) {
              // The trail traces where the ball was moments ago, so it stretches as every cleared
              // wave speeds it up.
              repeat(TRAIL) { index ->
                val past =
                  startY + (hitY - startY) * dial.progress(time - (index + 1) * TRAIL_STEP_MS)
                drawCircle(
                  Color.White.copy(alpha = 0.34f * (TRAIL - index) / TRAIL),
                  (6f - index * 0.7f).dp.toPx(),
                  Offset(at.x, past),
                )
              }
              drawCircle(
                Color.White,
                7.dp.toPx(),
                Offset(at.x, startY + (hitY - startY) * dial.progress(time)),
              )
            }
            // The ring starts the moment the pulse lands and takes its colour once the host settles
            // the catch.
            val elapsed = time - dial.catchAt
            if (elapsed in 0..450) {
              val progress = elapsed / 450f
              val color =
                when (dial.result) {
                  Catch.Pending -> Color.White.copy(alpha = 0.6f)
                  Catch.Caught -> Color.White
                  Catch.Missed -> Symbol.Triangle.color
                }.let { it.copy(alpha = it.alpha * (1f - progress)) }
              val radius = 10.dp.toPx() + progress * 34.dp.toPx()
              drawCircle(color, radius, Offset(at.x, hitY), style = Stroke(3.dp.toPx()))
              repeat(4) { index ->
                val angle = index * PI.toFloat() / 2f
                drawCircle(
                  color,
                  2.5.dp.toPx(),
                  Offset(at.x, hitY) + Offset(cos(angle), sin(angle)) * radius,
                )
              }
            }
          }
        }
    )
    ParticleLayer(fx.particles, Modifier.fillMaxSize())
  }
}

private const val TRAIL = 6
private const val TRAIL_STEP_MS = 28L
