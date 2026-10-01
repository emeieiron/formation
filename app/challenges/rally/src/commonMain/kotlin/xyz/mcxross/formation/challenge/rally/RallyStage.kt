package xyz.mcxross.formation.challenge.rally

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import xyz.mcxross.formation.challenge.FlashLayer
import xyz.mcxross.formation.challenge.Hud
import xyz.mcxross.formation.challenge.OnGesture
import xyz.mcxross.formation.challenge.StageScope
import xyz.mcxross.formation.challenge.onTouchDown
import xyz.mcxross.formation.challenge.rememberFlash
import xyz.mcxross.formation.challenge.rememberHostNow
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.PlayerLight
import xyz.mcxross.formation.design.effects.ParticleLayer
import xyz.mcxross.formation.design.effects.rememberParticles
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.sensors.Gesture

@Composable
internal fun RallyStage(scope: StageScope<RallyState, RallyInput>) {
  val state = scope.state
  val c = Theme.colors
  val me = scope.player(scope.me)
  val myColor = me?.light?.color ?: c.accent
  val now by rememberHostNow(scope.clock)
  val particles = rememberParticles()
  val flash = rememberFlash()
  var returned by remember { mutableIntStateOf(-1) }
  val ball = state.ball
  val mine = ball != null && ball.to == scope.me

  fun swing() {
    val b = scope.state.ball ?: return
    if (b.to != scope.me || returned == b.id) return
    val at = scope.clock.hostNow()
    if (at < b.arriveAt - b.window - RallyGame.IGNORE_BEFORE_MS) return
    returned = b.id
    scope.send(RallyInput(b.id, at))
    if (abs(at - b.arriveAt) <= b.window + RallyGame.TOLERANCE_MS) scope.haptics.confirm()
  }

  OnGesture(scope.motion, enabled = mine) { if (it == Gesture.SWING) swing() }

  LaunchedEffect(state.event) {
    when (val e = state.event) {
      is RallyEvent.Miss -> {
        scope.haptics.reject()
        flash.fire(c.negative)
      }
      is RallyEvent.Hit -> if (e.player != scope.me) scope.haptics.tick()
      null -> {}
    }
  }

  Column(Modifier.fillMaxSize()) {
    Hud(
      label = "${state.streak} / ${state.target} in a row",
      progress = state.streak.toFloat() / state.target,
      lives = state.lives,
      maxLives = state.maxLives,
      accent = c.light(1).color,
      trailing = if (state.best > 0) "Best ${state.best}" else null,
    )
    RallyOrbit(scope, state, now, Modifier.fillMaxWidth().height(150.dp))
    BoxWithConstraints(
      Modifier.weight(1f).fillMaxWidth().onTouchDown(scope.clock, enabled = mine) { _, _ ->
        swing()
      }
    ) {
      val density = LocalDensity.current
      val w = with(density) { maxWidth.toPx() }
      val h = with(density) { maxHeight.toPx() }
      val paddle = Offset(w / 2, h - with(density) { 96.dp.toPx() })

      LaunchedEffect(state.event) {
        val e = state.event
        if (e is RallyEvent.Hit && e.player == scope.me) {
          particles.burst(
            paddle,
            listOf(myColor, Color.White, c.reward),
            count = 30,
            direction = -90f,
            spread = 150f,
          )
        }
      }

      Canvas(Modifier.fillMaxSize()) { drawCourt(state, scope.me, now, paddle, myColor, c.line) }

      if (!mine) Waiting(scope, state, Modifier.align(Alignment.Center).padding(bottom = 80.dp))

      Column(
        Modifier.align(Alignment.BottomCenter).padding(bottom = Space.l),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          if (mine) "Tap or swing as it meets the ring" else "Get ready",
          style = Theme.type.subheadStrong,
          color = if (mine) c.content else c.contentTertiary,
          textAlign = TextAlign.Center,
        )
      }
      ParticleLayer(particles, Modifier.fillMaxSize())
      FlashLayer(flash)
    }
  }
}

@Composable
private fun RallyOrbit(
  scope: StageScope<RallyState, RallyInput>,
  state: RallyState,
  now: Long,
  modifier: Modifier,
) {
  val c = Theme.colors
  BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
    val density = LocalDensity.current
    val radius = with(density) { (minOf(maxWidth, maxHeight) / 2 - 22.dp).toPx() }
    val players = state.players.mapNotNull { scope.player(it) }
    val n = players.size
    fun at(i: Int) =
      ((-90.0 + i * 360.0 / n) * PI / 180).let {
        Offset(radius * cos(it).toFloat(), radius * sin(it).toFloat())
      }
    val positions = players.mapIndexed { i, p -> p.id to at(i) }.toMap()

    Canvas(Modifier.fillMaxSize()) {
      val mid = Offset(size.width / 2, size.height / 2)
      drawCircle(c.line, radius, mid, style = Stroke(1.dp.toPx()))
      val ball = state.ball ?: return@Canvas
      val from = ball.from?.let { positions[it] } ?: Offset.Zero
      val to = positions[ball.to] ?: return@Canvas
      val span = (ball.arriveAt - ball.launchAt).coerceAtLeast(1)
      val t = ((now - ball.launchAt).toFloat() / span).coerceIn(0f, 1f)
      val p = mid + from + (to - from) * t
      val color = ball.from?.let { scope.player(it)?.light?.color } ?: c.reward
      drawLine(
        Brush.linearGradient(listOf(Color.Transparent, color.copy(alpha = 0.6f)), mid + from, p),
        mid + from,
        p,
        3.dp.toPx(),
        StrokeCap.Round,
      )
      drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = 0.6f), Color.Transparent), p, 18.dp.toPx()),
        18.dp.toPx(),
        p,
      )
      drawCircle(Color.White, 5.dp.toPx(), p)
    }
    players.forEach { player ->
      val o = positions.getValue(player.id)
      val target = state.ball?.to == player.id
      Box(Modifier.offset(with(density) { o.x.toDp() }, with(density) { o.y.toDp() })) {
        PlayerLight(
          player.name,
          player.light,
          size = if (target) 40.dp else 30.dp,
          seeker = player.seeker,
          pulse = target,
          dimmed = !player.connected,
        )
      }
    }
  }
}

@Composable
private fun Waiting(
  scope: StageScope<RallyState, RallyInput>,
  state: RallyState,
  modifier: Modifier,
) {
  val c = Theme.colors
  val target = state.ball?.to?.let { scope.player(it) }
  var missLabel by remember { mutableIntStateOf(0) }
  LaunchedEffect(state.event) {
    if (state.event is RallyEvent.Miss) {
      missLabel = state.event.ball
      delay(1_400)
      missLabel = 0
    }
  }
  AnimatedContent(
    targetState = if (missLabel != 0) "miss" else target?.id?.value ?: "none",
    modifier = modifier,
    transitionSpec = { fadeIn() togetherWith fadeOut() },
    label = "waiting",
  ) { key ->
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      when {
        key == "miss" -> {
          val miss = state.event as? RallyEvent.Miss
          Text("Dropped", style = Theme.type.display, color = c.negative)
          Spacer(Modifier.height(Space.s))
          Text(
            "${scope.name(miss?.player)} ${if (miss?.early == true) "swung early" else "missed it"}",
            style = Theme.type.body,
            color = c.contentSecondary,
          )
        }
        target != null -> {
          Streak(state.streak)
          Spacer(Modifier.height(Space.l))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Heading to ", style = Theme.type.body, color = c.contentSecondary)
            PlayerLight(target.name, target.light, size = 22.dp, seeker = target.seeker)
            Spacer(Modifier.width(6.dp))
            Text(target.name, style = Theme.type.bodyStrong, color = target.light.color)
          }
        }
        else -> Streak(state.streak)
      }
    }
  }
}

@Composable
private fun Streak(streak: Int) {
  val c = Theme.colors
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(
      Icons.Flame,
      null,
      tint = if (streak > 0) c.light(1).color else c.contentDisabled,
      size = 36.dp,
    )
    Spacer(Modifier.width(Space.s))
    Text(
      streak.toString(),
      style = Theme.type.numeralLarge,
      color = if (streak > 0) c.content else c.contentTertiary,
    )
  }
}

private fun DrawScope.drawCourt(
  state: RallyState,
  me: xyz.mcxross.formation.model.PlayerId,
  now: Long,
  paddle: Offset,
  color: Color,
  line: Color,
) {
  val ball = state.ball
  val ballR = 14.dp.toPx()
  drawArc(
    Brush.horizontalGradient(
      listOf(Color.Transparent, color.copy(alpha = 0.9f), Color.Transparent),
      0f,
      size.width,
    ),
    startAngle = 200f,
    sweepAngle = 140f,
    useCenter = false,
    topLeft = Offset(size.width * 0.12f, paddle.y - 14.dp.toPx()),
    size = androidx.compose.ui.geometry.Size(size.width * 0.76f, size.height * 0.5f),
    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
  )
  drawCircle(line, ballR + 6.dp.toPx(), paddle, style = Stroke(1.dp.toPx()))

  if (ball == null) return
  val span = (ball.arriveAt - ball.launchAt).coerceAtLeast(1).toFloat()
  if (ball.to == me) {
    val t = (now - ball.launchAt) / span
    if (t < -0.05f) return
    val sway =
      sin(t.coerceIn(0f, 1f) * PI).toFloat() *
        size.width *
        0.18f *
        (if (ball.id % 2 == 0) 1 else -1)
    val y = -ballR + (paddle.y + ballR) * t
    val p = Offset(paddle.x + sway, y)
    // The timing ring closes on the paddle exactly when the spark arrives.
    val ring = ballR + (1f - t).coerceIn(0f, 1f) * 110.dp.toPx()
    drawCircle(
      color.copy(alpha = 0.25f + 0.6f * t.coerceIn(0f, 1f)),
      ring,
      paddle,
      style = Stroke(2.5.dp.toPx()),
    )
    val trail = Offset(p.x - sway * 0.08f, p.y - 70.dp.toPx())
    drawLine(
      Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.55f)), trail, p),
      trail,
      p,
      ballR * 1.1f,
      StrokeCap.Round,
    )
    drawCircle(
      Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent), p, ballR * 3.2f),
      ballR * 3.2f,
      p,
    )
    drawCircle(Brush.radialGradient(listOf(Color.White, color), p, ballR), ballR, p)
  } else if (ball.from == me) {
    val t = ((now - ball.launchAt) / 380f).coerceIn(0f, 1f)
    if (t >= 1f) return
    val p = Offset(paddle.x, paddle.y - (paddle.y + ballR * 2) * t)
    drawCircle(
      Brush.radialGradient(
        listOf(color.copy(alpha = 0.5f * (1 - t)), Color.Transparent),
        p,
        ballR * 3f,
      ),
      ballR * 3f,
      p,
    )
    drawCircle(Color.White.copy(alpha = 1f - t), ballR * (1f - 0.4f * t), p)
  }
}
