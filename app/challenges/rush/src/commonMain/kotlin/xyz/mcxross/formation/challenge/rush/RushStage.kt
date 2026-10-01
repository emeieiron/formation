package xyz.mcxross.formation.challenge.rush

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import xyz.mcxross.formation.challenge.Hud
import xyz.mcxross.formation.challenge.OnGesture
import xyz.mcxross.formation.challenge.StageScope
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.foundation.pressable
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.sensors.Gesture

internal val ReactorSystem.icon: ImageVector
  get() =
    when (this) {
      ReactorSystem.COOLANT -> Icons.Hold
      ReactorSystem.CHARGE -> Icons.Bolt
      ReactorSystem.BALANCE -> Icons.Tilt
      ReactorSystem.PRESSURE -> Icons.Shake
      ReactorSystem.SPIN -> Icons.Refresh
    }

@Composable
internal fun RushStage(scope: StageScope<RushState, RushInput>) {
  val state = scope.state
  val c = Theme.colors
  val mine = state.gauges.firstOrNull { scope.me in it.crew }
  LaunchedEffect(mine?.ok) { if (mine?.ok == false) scope.haptics.tick() }
  Column(Modifier.fillMaxSize()) {
    Hud(
      label = "${state.elapsed / 1000} / ${state.duration / 1000} s",
      progress = state.elapsed.toFloat() / state.duration,
      accent = c.light(0).color,
      trailing = "Level ${state.level}",
    )
    Core(scope, state, mine, Modifier.fillMaxWidth().height(250.dp))
    Box(
      Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.gutter),
      contentAlignment = Alignment.Center,
    ) {
      if (mine != null) Controls(scope, mine)
    }
  }
}

@Composable
private fun Core(
  scope: StageScope<RushState, RushInput>,
  state: RushState,
  mine: Gauge?,
  modifier: Modifier,
) {
  val c = Theme.colors
  val calm = c.celebration[1]
  val angry = c.negative
  val beat by
    rememberInfiniteTransition(label = "core")
      .animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "beat")
  Box(modifier, contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxSize()) {
      val mid = Offset(size.width / 2, size.height / 2)
      val core = size.minDimension * 0.17f
      val tint = lerp(angry, calm, state.stability)
      val throb =
        1f +
          (1f - state.stability) *
            0.12f *
            sin(beat * 2 * PI.toFloat() * (1f + 3f * (1f - state.stability)))
      drawCircle(
        Brush.radialGradient(listOf(tint.copy(alpha = 0.55f), Color.Transparent), mid, core * 2.6f),
        core * 2.6f,
        mid,
      )
      drawCircle(
        Brush.radialGradient(listOf(Color.White, tint, tint.copy(alpha = 0.6f)), mid, core * throb),
        core * throb,
        mid,
      )
      val ring = core * 1.35f
      drawArc(
        c.surfaceHigher,
        -90f,
        360f,
        false,
        mid - Offset(ring, ring),
        Size(ring * 2, ring * 2),
        style = Stroke(4.dp.toPx()),
      )
      drawArc(
        tint,
        -90f,
        360f * state.stability,
        false,
        mid - Offset(ring, ring),
        Size(ring * 2, ring * 2),
        style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
      )

      val n = state.gauges.size
      val radius = size.minDimension * 0.42f
      val gap = 14f
      state.gauges.forEachIndexed { k, g ->
        val sweep = 360f / n - gap
        val start = -90f + k * 360f / n + gap / 2
        val owner = scope.player(g.crew.firstOrNull())?.light?.color ?: c.content
        val box = Size(radius * 2, radius * 2)
        val topLeft = mid - Offset(radius, radius)
        val stroke = if (g == mine) 10.dp.toPx() else 7.dp.toPx()
        drawArc(
          c.surfaceHigher,
          start,
          sweep,
          false,
          topLeft,
          box,
          style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawArc(
          owner.copy(alpha = 0.3f),
          start + sweep * g.lo,
          sweep * (g.hi - g.lo),
          false,
          topLeft,
          box,
          style = Stroke(stroke),
        )
        val a = (start + sweep * g.value) * PI.toFloat() / 180f
        val needle = mid + Offset(radius * cos(a), radius * sin(a))
        drawCircle(if (g.ok) owner else c.negative, stroke * 0.9f, needle)
        drawCircle(Color.White, stroke * 0.35f, needle)
      }
    }
  }
}

@Composable
private fun Controls(scope: StageScope<RushState, RushInput>, gauge: Gauge) {
  val c = Theme.colors
  val color = scope.player(scope.me)?.light?.color ?: c.accent
  val mates = gauge.crew.filter { it != scope.me }.mapNotNull { scope.player(it)?.name }
  Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(gauge.system.icon, null, tint = color, size = 22.dp)
      Spacer(Modifier.width(Space.s))
      Text(gauge.system.title.uppercase(), style = Theme.type.title3, color = c.content)
      if (mates.isNotEmpty())
        Text(
          "  with ${mates.joinToString()}",
          style = Theme.type.footnote,
          color = c.contentSecondary,
        )
    }
    Spacer(Modifier.height(Space.m))
    GaugeBar(gauge, color)
    Spacer(Modifier.height(Space.l))
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      when (gauge.system) {
        ReactorSystem.COOLANT -> VentPad(scope, color)
        ReactorSystem.CHARGE -> PumpButton(scope, color)
        ReactorSystem.BALANCE -> Balance(scope, gauge, color)
        ReactorSystem.PRESSURE -> Pressure(scope, color)
        ReactorSystem.SPIN -> Dial(scope, color)
      }
    }
  }
}

@Composable
private fun GaugeBar(gauge: Gauge, color: Color) {
  val c = Theme.colors
  Canvas(Modifier.fillMaxWidth().height(22.dp)) {
    val y = size.height / 2
    val h = 10.dp.toPx()
    drawRoundRect(
      c.surfaceHigher,
      Offset(0f, y - h / 2),
      Size(size.width, h),
      androidx.compose.ui.geometry.CornerRadius(h / 2),
    )
    drawRect(
      color.copy(alpha = 0.35f),
      Offset(size.width * gauge.lo, y - h / 2),
      Size(size.width * (gauge.hi - gauge.lo), h),
    )
    val x = size.width * gauge.value
    drawCircle(if (gauge.ok) color else c.negative, 10.dp.toPx(), Offset(x, y))
    drawCircle(Color.White, 4.dp.toPx(), Offset(x, y))
  }
  Text(
    if (gauge.ok) "In the band" else if (gauge.value < gauge.lo) "Too low!" else "Too high!",
    style = Theme.type.caption,
    color = if (gauge.ok) c.contentSecondary else c.negative,
  )
}

@Composable
private fun VentPad(scope: StageScope<RushState, RushInput>, color: Color) {
  val c = Theme.colors
  var down by remember { mutableStateOf(false) }
  Box(
    Modifier.size(210.dp)
      .clip(Shapes.circle)
      .background(if (down) color.copy(alpha = 0.5f) else c.surfaceHigh)
      .pointerInput(Unit) {
        awaitEachGesture {
          awaitFirstDown()
          down = true
          scope.send(RushInput.Hold(true))
          waitForUpOrCancellation()
          down = false
          scope.send(RushInput.Hold(false))
        }
      },
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Icon(Icons.Hold, null, tint = if (down) c.content else color, size = 44.dp)
      Spacer(Modifier.height(Space.s))
      Text(if (down) "VENTING" else "HOLD TO VENT", style = Theme.type.headline, color = c.content)
    }
  }
}

@Composable
private fun PumpButton(scope: StageScope<RushState, RushInput>, color: Color) {
  val c = Theme.colors
  Box(
    Modifier.size(200.dp)
      .pressable(
        {
          scope.send(RushInput.Tap)
          scope.haptics.tick()
        },
        shape = Shapes.circle,
        squeeze = true,
      )
      .background(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), c.surfaceHigh))),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Icon(Icons.Bolt, null, tint = c.content, size = 44.dp)
      Text("PUMP", style = Theme.type.title2, color = c.content)
    }
  }
}

@Composable
private fun Pressure(scope: StageScope<RushState, RushInput>, color: Color) {
  val c = Theme.colors
  OnGesture(scope.motion) {
    if (it == Gesture.SHAKE) {
      scope.send(RushInput.Shake)
      scope.haptics.confirm()
    }
  }
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Icon(Icons.Shake, null, tint = color, size = 80.dp)
    Spacer(Modifier.height(Space.m))
    Text("SHAKE TO RELEASE", style = Theme.type.title2, color = c.content)
    Spacer(Modifier.height(Space.xs))
    Text(
      ReactorSystem.PRESSURE.hint,
      style = Theme.type.footnote,
      color = c.contentSecondary,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun Balance(scope: StageScope<RushState, RushInput>, gauge: Gauge, color: Color) {
  val c = Theme.colors
  var dragLean by remember { mutableStateOf<Float?>(null) }
  val tilt by scope.motion.tilt.collectAsState()
  // Tipping the right edge down rolls the core right, which is up the reading.
  val lean = dragLean ?: (-tilt.roll / 25f).coerceIn(-1f, 1f)
  LaunchedEffect(Unit) {
    var sent = Float.NaN
    while (true) {
      val now = dragLean ?: (-scope.motion.tilt.value.roll / 25f).coerceIn(-1f, 1f)
      if (sent.isNaN() || abs(now - sent) > 0.04f) {
        scope.send(RushInput.Tilt(now))
        sent = now
      }
      delay(90)
    }
  }
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
      Modifier.fillMaxWidth()
        .height(84.dp)
        .clip(Shapes.pill)
        .background(c.surfaceHigh)
        .pointerInput(Unit) {
          detectHorizontalDragGestures(
            onDragEnd = { dragLean = null },
            onDragCancel = { dragLean = null },
          ) { change, _ ->
            dragLean = ((change.position.x / size.width) * 2f - 1f).coerceIn(-1f, 1f)
          }
        },
      contentAlignment = Alignment.Center,
    ) {
      Canvas(Modifier.fillMaxSize().padding(horizontal = Space.l)) {
        val y = size.height / 2
        drawLine(c.lineStrong, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
        val x = size.width * (0.5f + lean * 0.5f)
        drawCircle(color.copy(alpha = 0.35f), 26.dp.toPx(), Offset(x, y))
        drawCircle(color, 14.dp.toPx(), Offset(x, y))
      }
    }
    Spacer(Modifier.height(Space.m))
    Text("TILT TO STEADY", style = Theme.type.title2, color = c.content)
    Text(
      "Or drag the push along the track",
      style = Theme.type.footnote,
      color = c.contentSecondary,
    )
    if (!gauge.ok)
      Text(
        if (gauge.value < gauge.lo) "Push right" else "Push left",
        style = Theme.type.headline,
        color = c.negative,
      )
  }
}

@Composable
private fun Dial(scope: StageScope<RushState, RushInput>, color: Color) {
  val c = Theme.colors
  var angle by remember { mutableFloatStateOf(0f) }
  var speed by remember { mutableFloatStateOf(0f) }
  LaunchedEffect(Unit) {
    while (true) {
      scope.send(RushInput.Spin(speed))
      speed *= 0.6f
      delay(120)
    }
  }
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
      Modifier.size(210.dp).pointerInput(Unit) {
        var lastAngle = 0f
        var lastTime = 0L
        detectDragGestures(
          onDragStart = { p ->
            lastAngle = atan2(p.y - size.height / 2f, p.x - size.width / 2f)
            lastTime = scope.clock.hostNow()
          }
        ) { change, _ ->
          val p = change.position
          val a = atan2(p.y - size.height / 2f, p.x - size.width / 2f)
          var delta = a - lastAngle
          if (delta > PI) delta -= (2 * PI).toFloat()
          if (delta < -PI) delta += (2 * PI).toFloat()
          lastAngle = a
          val now = scope.clock.hostNow()
          val dt = ((now - lastTime).coerceAtLeast(8)) / 1000f
          lastTime = now
          angle += delta * 180f / PI.toFloat()
          speed = (speed * 0.7f + abs(delta) / (2 * PI.toFloat()) / dt * 0.3f).coerceAtMost(6f)
        }
      },
      contentAlignment = Alignment.Center,
    ) {
      Canvas(Modifier.fillMaxSize()) {
        val mid = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2 - 6.dp.toPx()
        drawCircle(c.surfaceHigh, r, mid)
        drawCircle(
          color.copy(alpha = 0.4f + 0.1f * speed.coerceAtMost(4f)),
          r,
          mid,
          style = Stroke(3.dp.toPx()),
        )
        rotate(angle, mid) {
          for (i in 0 until 12) {
            val a = i * 30f * PI.toFloat() / 180f
            drawLine(
              c.lineStrong,
              mid + Offset(cos(a), sin(a)) * (r * 0.72f),
              mid + Offset(cos(a), sin(a)) * (r * 0.9f),
              3.dp.toPx(),
              StrokeCap.Round,
            )
          }
          drawCircle(color, r * 0.12f, mid + Offset(0f, -r * 0.62f))
        }
      }
      Icon(Icons.Refresh, null, tint = c.contentSecondary, size = 32.dp)
    }
    Spacer(Modifier.height(Space.m))
    Text("SPIN THE DIAL", style = Theme.type.title2, color = c.content)
  }
}
