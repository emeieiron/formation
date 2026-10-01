package xyz.mcxross.formation.design.effects

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.EmptySlot
import xyz.mcxross.formation.design.components.PlayerLight
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.tokens.Light
import xyz.mcxross.formation.design.tokens.Motion
import xyz.mcxross.formation.design.tokens.Shapes

@Immutable
data class RingMember(
  val name: String,
  val light: Light,
  val seeker: Boolean = false,
  val connected: Boolean = true,
  val ready: Boolean = false,
  val me: Boolean = false,
)

@Composable
fun FormationRing(
  members: List<RingMember?>,
  modifier: Modifier = Modifier,
  complete: Boolean = false,
  lightSize: Dp = 52.dp,
  showNames: Boolean = members.size <= 8,
  center: @Composable BoxScope.() -> Unit = {},
) {
  val c = Theme.colors
  val joined by
    animateFloatAsState(if (complete) 1f else 0f, Motion.emphasized(900), label = "complete")
  BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
    val density = LocalDensity.current
    val side = min(constraints.maxWidth, constraints.maxHeight).toFloat()
    val lightPx = with(density) { lightSize.toPx() }
    val radius = side / 2f - lightPx * (if (showNames) 0.95f else 0.6f)
    val n = members.size.coerceAtLeast(1)
    val points =
      List(n) { i ->
        val a = (-90.0 + i * 360.0 / n) * PI / 180
        Offset((radius * cos(a)).toFloat(), (radius * sin(a)).toFloat())
      }

    Canvas(Modifier.fillMaxSize()) {
      val mid = Offset(size.width / 2, size.height / 2)
      drawCircle(
        c.line,
        radius,
        mid,
        style = Stroke(1.dp.toPx()),
      )
      val gap = (lightPx * 0.62f / radius) * 180f / PI.toFloat()
      drawLinks(members, radius, gap, mid, joined, c.lineStrong, c.celebration)
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = center)

    members.forEachIndexed { i, member ->
      val p = points[i]
      val offset = with(density) { DpOffset(p.x.toDp(), p.y.toDp()) }
      Column(
        Modifier.offset(offset.x, offset.y + if (showNames) 8.dp else 0.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        if (member == null) EmptySlot(size = lightSize) else ArrivingLight(member, lightSize)
        if (showNames) {
          Text(
            member?.let { if (it.me) "You" else it.name } ?: "Open",
            style = Theme.type.caption,
            color =
              if (member == null) c.contentTertiary
              else if (member.connected) c.content else c.contentTertiary,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(lightSize + 28.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun ArrivingLight(member: RingMember, size: Dp) {
  val scale = remember { Animatable(0.3f) }
  LaunchedEffect(Unit) { scale.animateTo(1f, Motion.bouncy()) }
  val outline = Theme.colors.content
  Box(
    Modifier.size(size).then(
      if (member.me && !member.seeker) Modifier.border(1.dp, outline, Shapes.circle) else Modifier
    ),
    contentAlignment = Alignment.Center,
  ) {
    PlayerLight(
      member.name,
      member.light,
      Modifier.size(size * scale.value),
      size = size * scale.value,
      seeker = member.seeker,
      dimmed = !member.connected,
    )
  }
}

// Arcs between neighbours stop short of each light; once [joined] rises, an aurora ring closes the
// loop.
private fun DrawScope.drawLinks(
  members: List<RingMember?>,
  radius: Float,
  gapDegrees: Float,
  mid: Offset,
  joined: Float,
  line: Color,
  aurora: List<Color>,
) {
  val n = members.size
  if (n < 2) return
  val segment = 360f / n
  val topLeft = mid - Offset(radius, radius)
  val box = Size(radius * 2, radius * 2)
  for (i in 0 until n) {
    if (members[i] == null || members[(i + 1) % n] == null) continue
    drawArc(
      line,
      -90f + i * segment + gapDegrees,
      segment - 2 * gapDegrees,
      false,
      topLeft,
      box,
      style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round),
    )
  }
  if (joined > 0f) {
    val ring = Brush.sweepGradient(aurora + aurora.first(), mid)
    drawArc(
      ring,
      -90f,
      360f * joined,
      false,
      topLeft,
      box,
      alpha = 0.25f,
      style = Stroke(14.dp.toPx() * joined, cap = StrokeCap.Round),
    )
    drawArc(
      ring,
      -90f,
      360f * joined,
      false,
      topLeft,
      box,
      style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
    )
  }
}

@Composable
fun FormationMark(
  modifier: Modifier = Modifier,
  progress: Float = 1f,
  lights: List<Light> = Theme.colors.lights,
) {
  val c = Theme.colors
  val colors = listOf(lights[5], lights[6], lights[3], lights[4], lights[1])
  Canvas(modifier) {
    val w = size.width
    val h = size.height
    val nodes =
      listOf(
        Offset(0.1f * w, 0.78f * h),
        Offset(0.3f * w, 0.52f * h),
        Offset(0.5f * w, 0.22f * h),
        Offset(0.7f * w, 0.52f * h),
        Offset(0.9f * w, 0.78f * h),
      )
    val seg = 1f / (nodes.size - 1)
    for (i in 0 until nodes.size - 1) {
      val local = ((progress - i * seg) / seg).coerceIn(0f, 1f)
      if (local <= 0f) continue
      val from = nodes[i]
      val to = from + (nodes[i + 1] - from) * local
      drawLine(
        Brush.linearGradient(listOf(colors[i].color, colors[i + 1].color), nodes[i], nodes[i + 1]),
        from,
        to,
        w * 0.022f,
        StrokeCap.Round,
      )
    }
    nodes.forEachIndexed { i, p ->
      val on = (progress * (nodes.size - 1) + 0.6f - i).coerceIn(0f, 1f)
      val r = w * (if (i == 2) 0.085f else 0.065f)
      drawCircle(
        Brush.radialGradient(
          listOf(colors[i].color.copy(alpha = 0.5f * on), Color.Transparent),
          p,
          r * 2.4f,
        ),
        r * 2.4f,
        p,
      )
      drawCircle(colors[i].color.copy(alpha = on), r * on.coerceAtLeast(0.001f), p)
      drawCircle(
        c.highlight.copy(alpha = 0.55f * on),
        r * 0.38f * on,
        p + Offset(-r * 0.22f, -r * 0.22f),
      )
    }
    if (progress <= 0f) drawCircle(c.line, w * 0.02f, nodes[2])
  }
}
