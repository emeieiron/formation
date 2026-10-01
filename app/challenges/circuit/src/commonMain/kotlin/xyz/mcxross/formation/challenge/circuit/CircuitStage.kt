package xyz.mcxross.formation.challenge.circuit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import xyz.mcxross.formation.challenge.ActionPrompt
import xyz.mcxross.formation.challenge.FlashLayer
import xyz.mcxross.formation.challenge.Hud
import xyz.mcxross.formation.challenge.OnGesture
import xyz.mcxross.formation.challenge.StageScope
import xyz.mcxross.formation.challenge.onTouchDown
import xyz.mcxross.formation.challenge.rememberFlash
import xyz.mcxross.formation.challenge.rememberHostNow
import xyz.mcxross.formation.challenge.rememberPose
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.PlayerLight
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.foundation.pressable
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.sensors.Gesture
import xyz.mcxross.formation.sensors.Pose

@Composable
internal fun CircuitStage(scope: StageScope<CircuitState, CircuitInput>) {
  val state = scope.state
  val c = Theme.colors
  val now by rememberHostNow(scope.clock)
  val flash = rememberFlash()
  val me = state.nodes.indexOf(scope.me)
  val pulse = state.pulse
  val mine = pulse != null && (pulse.node == me || pulse.partner == me)
  val calling =
    pulse != null && pulse.action == Action.MATCH && pulse.caller == me && pulse.node != me

  LaunchedEffect(state.event) {
    when (val e = state.event) {
      is CircuitEvent.Broke -> {
        scope.haptics.reject()
        flash.fire(c.negative)
      }
      is CircuitEvent.Passed -> if (e.node == me) scope.haptics.confirm()
      null -> {}
    }
  }
  LaunchedEffect(pulse?.id, mine) { if (mine) scope.haptics.heavy() }

  Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
      Hud(
        label = "Loop ${(state.loop + 1).coerceAtMost(state.loops)} of ${state.loops}",
        progress = state.passed.toFloat() / state.total,
        lives = state.lives,
        maxLives = state.maxLives,
        accent = c.light(4).color,
      )
      Board(scope, state, now, me, Modifier.fillMaxWidth().height(250.dp))
      Box(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.gutter),
        contentAlignment = Alignment.Center,
      ) {
        when {
          mine -> key(pulse!!.id) { Move(scope, pulse, now) }
          calling -> Caller(scope, pulse!!)
          else -> Watching(scope, state, me)
        }
      }
    }
    FlashLayer(flash)
  }
}

@Composable
private fun Move(scope: StageScope<CircuitState, CircuitInput>, pulse: Pulse, now: Long) {
  val c = Theme.colors
  val color = scope.player(scope.me)?.light?.color ?: c.accent
  var sent by remember { mutableStateOf(false) }
  val remaining =
    ((pulse.deadline - now).toFloat() / (pulse.deadline - pulse.arriveAt)).coerceIn(0f, 1f)
  fun done(choice: Int? = null) {
    if (sent) return
    sent = true
    scope.send(CircuitInput(pulse.id, pulse.action, scope.clock.hostNow(), choice))
  }

  when (pulse.action) {
    Action.TAP ->
      Box(
        Modifier.fillMaxSize().onTouchDown(scope.clock) { _, _ -> done() },
        contentAlignment = Alignment.Center,
      ) {
        ActionPrompt(
          Icons.Tap,
          "Tap",
          color,
          hint = "Anywhere on the screen",
          remaining = remaining,
        )
      }
    Action.HOLD -> HoldPad(color, remaining, onHeld = { done() })
    Action.SHAKE -> {
      OnGesture(scope.motion) { if (it == Gesture.SHAKE) done() }
      ActionPrompt(Icons.Shake, "Shake", color, hint = Action.SHAKE.hint, remaining = remaining)
    }
    Action.TURN -> {
      val pose by rememberPose(scope.motion)
      val start = remember { pose.sideways }
      LaunchedEffect(pose) { if (pose != Pose.TILTED && pose.sideways != start) done() }
      ActionPrompt(
        Icons.Rotate,
        "Turn",
        color,
        hint = if (start) "Turn it back upright" else Action.TURN.hint,
        remaining = remaining,
      )
    }
    Action.FLIP -> {
      val pose by rememberPose(scope.motion)
      LaunchedEffect(pose) { if (pose == Pose.FACE_DOWN) done() }
      ActionPrompt(Icons.Flip, "Flip", color, hint = Action.FLIP.hint, remaining = remaining)
    }
    Action.MATCH -> {
      val caller = scope.player(pulse.caller?.let { scope.state.nodes.getOrNull(it) })
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("MATCH", style = Theme.type.hero, color = c.content)
        Spacer(Modifier.height(Space.s))
        Text(
          "${caller?.name ?: "Your neighbour"} can see the symbol. Ask!",
          style = Theme.type.callout,
          color = c.contentSecondary,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Space.xl))
        pulse.options.chunked(2).forEach { row ->
          Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            row.forEach { option ->
              Box(
                Modifier.size(104.dp)
                  .pressable({ done(option) }, shape = Shapes.card, squeeze = true)
                  .background(c.surfaceHigh)
                  .border(1.dp, c.line, Shapes.card),
                contentAlignment = Alignment.Center,
              ) {
                SymbolGlyph(option, color, Modifier.size(52.dp))
              }
            }
          }
          Spacer(Modifier.height(Space.m))
        }
        Deadline(remaining, color)
      }
    }
    Action.SYNC -> {
      val nodes = scope.state.nodes
      val partnerIndex = if (pulse.node == nodes.indexOf(scope.me)) pulse.partner else pulse.node
      val partner = scope.player(partnerIndex?.let { nodes.getOrNull(it) })
      val waiting = nodes.indexOf(scope.me) in pulse.synced
      Box(
        Modifier.fillMaxSize().onTouchDown(scope.clock, enabled = !sent) { _, _ -> done() },
        contentAlignment = Alignment.Center,
      ) {
        ActionPrompt(
          Icons.Target,
          if (waiting) "Waiting" else "Sync",
          partner?.light?.color ?: color,
          hint = "Tap at the same moment as ${partner?.name ?: "your partner"}. Count it in!",
          remaining = remaining,
        )
      }
    }
  }
}

@Composable
private fun HoldPad(color: Color, remaining: Float, onHeld: () -> Unit) {
  val c = Theme.colors
  val fill = remember { Animatable(0f) }
  val scope = rememberCoroutineScope()
  var job by remember { mutableStateOf<Job?>(null) }
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
      Modifier.size(200.dp).pointerInput(Unit) {
        awaitEachGesture {
          awaitFirstDown()
          job = scope.launch {
            fill.animateTo(1f, tween(HOLD_MS))
            onHeld()
          }
          waitForUpOrCancellation()
          if (fill.value < 1f) {
            job?.cancel()
            scope.launch { fill.animateTo(0f, tween(180)) }
          }
        }
      },
      contentAlignment = Alignment.Center,
    ) {
      Canvas(Modifier.fillMaxSize()) {
        val stroke = 8.dp.toPx()
        drawCircle(
          Brush.radialGradient(
            listOf(color.copy(alpha = 0.18f + 0.3f * fill.value), Color.Transparent)
          ),
          size.minDimension / 2,
        )
        drawCircle(c.surfaceHigher, size.minDimension / 2 - stroke, style = Stroke(stroke))
        drawArc(
          color,
          -90f,
          360f * fill.value,
          false,
          Offset(stroke, stroke),
          androidx.compose.ui.geometry.Size(size.width - stroke * 2, size.height - stroke * 2),
          style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawCircle(color.copy(alpha = 0.9f), size.minDimension * (0.18f + 0.1f * fill.value))
      }
      Icon(Icons.Hold, null, tint = c.onInverse, size = 36.dp)
    }
    Spacer(Modifier.height(Space.l))
    Text("HOLD", style = Theme.type.hero, color = c.content)
    Spacer(Modifier.height(Space.s))
    Text(Action.HOLD.hint, style = Theme.type.callout, color = c.contentSecondary)
    Spacer(Modifier.height(Space.l))
    Deadline(remaining, color)
  }
}

@Composable
private fun Deadline(remaining: Float, color: Color) {
  val c = Theme.colors
  Box(Modifier.width(160.dp).height(4.dp).clip(Shapes.pill).background(c.surfaceHigher)) {
    Box(Modifier.fillMaxWidth(remaining).height(4.dp).clip(Shapes.pill).background(color))
  }
}

@Composable
private fun Caller(scope: StageScope<CircuitState, CircuitInput>, pulse: Pulse) {
  val c = Theme.colors
  val target = scope.player(scope.state.nodes.getOrNull(pulse.node))
  val symbol = pulse.symbol ?: return
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text("CALL IT OUT", style = Theme.type.overline, color = c.reward)
    Spacer(Modifier.height(Space.m))
    SymbolGlyph(symbol, c.reward, Modifier.size(150.dp))
    Spacer(Modifier.height(Space.l))
    Text(SymbolNames[symbol], style = Theme.type.display, color = c.content)
    Spacer(Modifier.height(Space.s))
    Text(
      "Tell ${target?.name ?: "your neighbour"} which shape to pick.",
      style = Theme.type.callout,
      color = c.contentSecondary,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun Watching(scope: StageScope<CircuitState, CircuitInput>, state: CircuitState, me: Int) {
  val c = Theme.colors
  val pulse = state.pulse
  val n = state.nodes.size
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    if (pulse == null) {
      val broke = state.event as? CircuitEvent.Broke
      if (broke != null) {
        Text("Short circuit", style = Theme.type.display, color = c.negative)
        Spacer(Modifier.height(Space.s))
        Text(
          "${scope.name(state.nodes.getOrNull(broke.node))} ${broke.reason}",
          style = Theme.type.body,
          color = c.contentSecondary,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Space.s))
        Text("Restarting from there…", style = Theme.type.footnote, color = c.contentTertiary)
      }
      return@Column
    }
    val steps = (me - pulse.node + n) % n
    val holder = scope.player(state.nodes.getOrNull(pulse.node))
    Text(
      when (steps) {
        1 -> "You're next"
        0 -> "Your partner is up"
        else -> "$steps nodes away"
      },
      style = Theme.type.display,
      color = if (steps == 1) c.content else c.contentSecondary,
    )
    Spacer(Modifier.height(Space.s))
    Text(
      "${holder?.name ?: "Someone"} must ${pulse.action.verb.lowercase()}",
      style = Theme.type.body,
      color = c.contentSecondary,
    )
  }
}

@Composable
private fun Board(
  scope: StageScope<CircuitState, CircuitInput>,
  state: CircuitState,
  now: Long,
  me: Int,
  modifier: Modifier,
) {
  val c = Theme.colors
  val accent = c.light(4).color
  BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
    val density = LocalDensity.current
    val w = with(density) { maxWidth.toPx() } / 2 - with(density) { 34.dp.toPx() }
    val h = with(density) { maxHeight.toPx() } / 2 - with(density) { 30.dp.toPx() }
    val n = state.nodes.size
    val points =
      List(n) { i ->
        val a = (-90.0 + i * 360.0 / n) * PI / 180
        Offset(w * cos(a).toFloat(), h * sin(a).toFloat())
      }
    val pulse = state.pulse
    val broken = (state.event as? CircuitEvent.Broke)?.takeIf { pulse == null }?.node
    Canvas(Modifier.fillMaxSize()) {
      val mid = Offset(size.width / 2, size.height / 2)
      for (i in 0 until n) {
        val a = mid + points[i]
        val b = mid + points[(i + 1) % n]
        val hot = pulse != null && (i + 1) % n == pulse.node
        drawLine(
          if (hot) accent.copy(alpha = 0.5f) else c.lineStrong,
          a,
          b,
          2.dp.toPx(),
          StrokeCap.Round,
        )
        drawCircle(c.lineStrong, 2.5.dp.toPx(), a + (b - a) * 0.5f)
      }
      if (pulse != null) {
        val t =
          ((now - (pulse.arriveAt - CircuitGame.TRAVEL_MS)).toFloat() / CircuitGame.TRAVEL_MS)
            .coerceIn(0f, 1f)
        val from = mid + points[(pulse.node - 1 + n) % n]
        val to = mid + points[pulse.node]
        val p = from + (to - from) * t
        drawLine(
          Brush.linearGradient(listOf(Color.Transparent, accent), from, p),
          from,
          p,
          4.dp.toPx(),
          StrokeCap.Round,
        )
        drawCircle(
          Brush.radialGradient(
            listOf(Color.White, accent.copy(alpha = 0.6f), Color.Transparent),
            p,
            22.dp.toPx(),
          ),
          22.dp.toPx(),
          p,
        )
        for (node in listOfNotNull(pulse.node, pulse.partner)) {
          if (t < 1f && node == pulse.node) continue
          val beat = 0.5f + 0.5f * sin(now / 140.0).toFloat()
          drawCircle(
            accent.copy(alpha = 0.35f + 0.35f * beat),
            30.dp.toPx(),
            mid + points[node],
            style = Stroke(3.dp.toPx()),
          )
        }
      }
      broken?.let {
        drawCircle(
          c.negative.copy(alpha = 0.7f),
          30.dp.toPx(),
          mid + points[it],
          style = Stroke(3.dp.toPx()),
        )
      }
      if (me >= 0)
        drawCircle(
          c.content.copy(alpha = 0.5f),
          25.dp.toPx(),
          mid + points[me],
          style = Stroke(1.dp.toPx()),
        )
    }
    state.nodes.forEachIndexed { i, id ->
      val player = scope.player(id) ?: return@forEachIndexed
      val o = points[i]
      Box(Modifier.offset(with(density) { o.x.toDp() }, with(density) { o.y.toDp() })) {
        PlayerLight(
          player.name,
          player.light,
          size = 36.dp,
          seeker = player.seeker,
          dimmed = !player.connected,
        )
      }
    }
  }
}

private const val HOLD_MS = 900
