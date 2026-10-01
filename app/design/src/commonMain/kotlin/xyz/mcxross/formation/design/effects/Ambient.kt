package xyz.mcxross.formation.design.effects

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.tokens.Motion

private class Star(
  val x: Float,
  val y: Float,
  val size: Float,
  val phase: Float,
  val speed: Float,
  val warm: Boolean,
)

@Composable
fun Starfield(
  modifier: Modifier = Modifier.fillMaxSize(),
  count: Int = 70,
  seed: Int = 7,
  brightness: Float = 1f,
) {
  val stars =
    remember(count, seed) {
      val r = Random(seed)
      List(count) {
        Star(
          r.nextFloat(),
          r.nextFloat(),
          0.6f + r.nextFloat() * 1.5f,
          r.nextFloat() * 6.28f,
          0.4f + r.nextFloat() * 1.2f,
          r.nextInt(5) == 0,
        )
      }
    }
  val t by
    rememberInfiniteTransition(label = "stars")
      .animateFloat(0f, 1f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "t")
  val warm = Theme.colors.reward
  Canvas(modifier) {
    val time = t * 60f
    for (s in stars) {
      val twinkle = 0.35f + 0.65f * (0.5f + 0.5f * sin(time * s.speed + s.phase))
      val drift = (s.y + t * 0.02f * s.speed) % 1f
      drawCircle(
        (if (s.warm) warm else Color.White).copy(alpha = 0.55f * twinkle * brightness),
        radius = s.size.dp.toPx() * 0.5f * (0.7f + 0.3f * twinkle),
        center = Offset(s.x * size.width, drift * size.height),
      )
    }
  }
}

@Composable
fun AuroraBackdrop(
  modifier: Modifier = Modifier.fillMaxSize(),
  intensity: Float = 1f,
  colors: List<Color> = Theme.colors.celebration,
) {
  val t by
    rememberInfiniteTransition(label = "aurora")
      .animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(18_000, easing = Motion.standard), RepeatMode.Reverse),
        label = "t",
      )
  Canvas(modifier) {
    colors.forEachIndexed { i, color ->
      val phase = t * 2f * PI.toFloat() + i * 2.1f
      val cx = size.width * (0.2f + 0.3f * i + 0.08f * sin(phase))
      val cy = size.height * (0.05f + 0.06f * cos(phase * 0.7f))
      val r = size.width * (0.75f + 0.1f * sin(phase * 1.3f))
      drawCircle(
        Brush.radialGradient(
          listOf(color.copy(alpha = 0.22f * intensity), Color.Transparent),
          Offset(cx, cy),
          r,
        ),
        r,
        Offset(cx, cy),
      )
    }
  }
}

@Composable
fun Radar(modifier: Modifier = Modifier, color: Color = Theme.colors.accent) {
  val motion = rememberInfiniteTransition(label = "radar")
  val ripple by
    motion.animateFloat(
      0f,
      1f,
      infiniteRepeatable(tween(2_600, easing = LinearEasing)),
      label = "ripple",
    )
  val sweep by
    motion.animateFloat(
      0f,
      360f,
      infiniteRepeatable(tween(3_600, easing = LinearEasing)),
      label = "sweep",
    )
  Canvas(modifier) {
    val max = size.minDimension / 2
    for (k in 0 until 3) {
      val p = (ripple + k / 3f) % 1f
      drawCircle(
        color.copy(alpha = 0.5f * (1f - p)),
        radius = max * p,
        style = Stroke(1.5.dp.toPx()),
      )
    }
    drawCircle(color.copy(alpha = 0.12f), radius = max, style = Stroke(1.dp.toPx()))
    drawCircle(color.copy(alpha = 0.12f), radius = max * 0.5f, style = Stroke(1.dp.toPx()))
    rotate(sweep) {
      drawArc(
        Brush.sweepGradient(
          listOf(Color.Transparent, color.copy(alpha = 0.0f), color.copy(alpha = 0.32f)),
          center,
        ),
        startAngle = -60f,
        sweepAngle = 60f,
        useCenter = true,
      )
    }
    drawCircle(color, radius = 4.dp.toPx())
  }
}

fun Modifier.shimmer(color: Color = Color.White, every: Int = 3_200): Modifier = composed {
  val p by
    rememberInfiniteTransition(label = "shimmer")
      .animateFloat(
        -0.4f,
        1.4f,
        infiniteRepeatable(tween(every, easing = Motion.standard)),
        label = "p",
      )
  drawWithContent {
    drawContent()
    val x = p * size.width
    drawRect(
      Brush.linearGradient(
        listOf(Color.Transparent, color.copy(alpha = 0.13f), Color.Transparent),
        start = Offset(x - size.width * 0.25f, 0f),
        end = Offset(x + size.width * 0.25f, size.height),
      )
    )
  }
}
