package xyz.mcxross.formation.challenge.sync

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.delay
import xyz.mcxross.formation.challenge.Hud
import xyz.mcxross.formation.challenge.OnGesture
import xyz.mcxross.formation.challenge.StageScope
import xyz.mcxross.formation.challenge.onTouchDown
import xyz.mcxross.formation.challenge.rememberHostNow
import xyz.mcxross.formation.challenge.rememberPose
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.PlayerLight
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.sensors.Gesture
import xyz.mcxross.formation.sensors.Pose

internal val SyncTask.icon: ImageVector
  get() =
    when (this) {
      SyncTask.TAP -> Icons.Tap
      SyncTask.DOUBLE_TAP -> Icons.Tap
      SyncTask.SWIPE_UP -> Icons.ArrowUpRight
      SyncTask.SWIPE_DOWN -> Icons.ChevronDown
      SyncTask.SHAKE -> Icons.Shake
      SyncTask.FLIP -> Icons.Flip
      SyncTask.TURN -> Icons.Rotate
      SyncTask.COVER -> Icons.Cover
    }

@Composable
internal fun SyncStage(scope: StageScope<SyncState, SyncInput>) {
  val state = scope.state
  val c = Theme.colors
  Column(Modifier.fillMaxSize()) {
    Hud(
      label = "Round ${(state.done + 1).coerceAtMost(state.rounds)} of ${state.rounds}",
      progress = state.done.toFloat() / state.rounds,
      lives = state.lives,
      maxLives = state.maxLives,
      accent = c.light(6).color,
    )
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      val round = state.round
      val result = state.result
      AnimatedContent(
        targetState =
          round?.attempt?.let { "round-$it" } ?: result?.attempt?.let { "result-$it" } ?: "idle",
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "sync",
      ) { key ->
        when {
          key.startsWith("round") && round != null -> Round(scope, round)
          key.startsWith("result") && result != null -> Result(scope, result)
          else -> Spacer(Modifier.fillMaxSize())
        }
      }
    }
  }
}

@Composable
private fun Round(scope: StageScope<SyncState, SyncInput>, round: SyncRound) {
  val c = Theme.colors
  val task = round.tasks[scope.me.value] ?: return
  val color = scope.player(scope.me)?.light?.color ?: c.accent
  val now by rememberHostNow(scope.clock)
  var sent by remember { mutableStateOf(false) }

  fun act(at: Long) {
    if (sent || at < round.countFrom - SyncGame.EARLY_MS) return
    sent = true
    scope.send(SyncInput(round.attempt, at))
    scope.haptics.confirm()
  }

  // Every phone ticks at the same instants, straight from the Seeker's clock, until the countdown
  // goes dark.
  LaunchedEffect(round.attempt) {
    for (k in 0 until SyncGame.BEATS) {
      val beat = round.countFrom + k * SyncGame.BEAT_MS
      if (round.blindFrom != null && beat >= round.blindFrom) break
      delay((beat - scope.clock.hostNow()).coerceAtLeast(0))
      scope.haptics.tick()
    }
  }

  TaskInput(scope, task, round, ::act)

  val blind = round.blindFrom != null && now >= round.blindFrom
  val beatsLeft = ((round.moment - now + SyncGame.BEAT_MS - 1) / SyncGame.BEAT_MS).toInt()
  Column(
    Modifier.fillMaxSize().padding(horizontal = Space.gutter),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(Modifier.height(Space.l))
    Text(
      when {
        sent -> "Sent. Waiting for the others"
        now < round.countFrom -> "Get ready"
        blind -> "Count it out loud!"
        beatsLeft in 1..SyncGame.BEATS -> beatsLeft.toString()
        else -> "Now!"
      },
      style =
        if (!sent && !blind && now >= round.countFrom && beatsLeft in 1..SyncGame.BEATS)
          Theme.type.numeralLarge
        else Theme.type.title2,
      color = if (blind) c.reward else c.content,
    )
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      Rings(round, now, color, blind, sent)
      Icon(task.icon, null, tint = if (sent) c.positive else color, size = 64.dp)
    }
    Text(
      task.verb.uppercase(),
      style = Theme.type.hero,
      color = c.content,
      textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(Space.s))
    Text(
      task.hint,
      style = Theme.type.callout,
      color = c.contentSecondary,
      textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(Space.l))
    Acted(scope, round)
    Spacer(Modifier.height(Space.l))
  }
}

@Composable
private fun Rings(round: SyncRound, now: Long, color: Color, blind: Boolean, sent: Boolean) {
  val c = Theme.colors
  Canvas(Modifier.size(280.dp)) {
    val target = 62.dp.toPx()
    val outer = size.minDimension / 2 - 4.dp.toPx()
    val span = (round.moment - round.countFrom).toFloat()
    val t = ((now - round.countFrom) / span).coerceIn(0f, 1f)
    val sinceBeat = ((now - round.countFrom).mod(SyncGame.BEAT_MS)).toFloat() / SyncGame.BEAT_MS
    val beatGlow =
      if (now >= round.countFrom && !blind && now < round.moment) (1f - sinceBeat).coerceIn(0f, 1f)
      else 0f
    drawCircle(
      Brush.radialGradient(listOf(color.copy(alpha = 0.12f + 0.25f * beatGlow), Color.Transparent)),
      target * 2.2f,
    )
    drawCircle(
      if (sent) c.positive else color,
      target,
      style = Stroke(3.dp.toPx() + 3.dp.toPx() * beatGlow),
    )
    if (!blind && now < round.moment + 200) {
      val r = target + (outer - target) * (1f - t)
      drawCircle(
        color.copy(alpha = if (now < round.countFrom) 0.25f else 0.85f),
        r,
        style = Stroke(2.dp.toPx()),
      )
    }
    if (blind) {
      drawCircle(
        c.reward.copy(alpha = 0.35f),
        outer,
        style =
          Stroke(
            1.dp.toPx(),
            pathEffect =
              androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                floatArrayOf(6.dp.toPx(), 8.dp.toPx())
              ),
          ),
      )
    }
  }
}

@Composable
private fun TaskInput(
  scope: StageScope<SyncState, SyncInput>,
  task: SyncTask,
  round: SyncRound,
  act: (Long) -> Unit,
) {
  when (task) {
    SyncTask.SHAKE ->
      OnGesture(scope.motion) { if (it == Gesture.SHAKE) act(scope.clock.hostNow()) }
    SyncTask.COVER ->
      OnGesture(scope.motion) { if (it == Gesture.COVER) act(scope.clock.hostNow()) }
    SyncTask.FLIP -> {
      val pose by rememberPose(scope.motion)
      LaunchedEffect(pose) { if (pose == Pose.FACE_DOWN) act(scope.clock.hostNow()) }
    }
    SyncTask.TURN -> {
      val pose by rememberPose(scope.motion)
      val start = remember(round.attempt) { (pose?.sideways ?: false) }
      LaunchedEffect(pose) {
        if (pose != null && pose != Pose.TILTED && (pose?.sideways ?: false) != start) act(scope.clock.hostNow())
      }
    }
    else -> TouchCatcher(scope, task, act)
  }
}

@Composable
private fun TouchCatcher(
  scope: StageScope<SyncState, SyncInput>,
  task: SyncTask,
  act: (Long) -> Unit,
) {
  val density = LocalDensity.current
  val threshold = with(density) { 48.dp.toPx() }
  var lastTap by remember { mutableLongStateOf(Long.MIN_VALUE / 2) }
  var dragged by remember { mutableStateOf(0f) }
  Box(
    Modifier.fillMaxSize()
      .then(
        when (task) {
          SyncTask.TAP -> Modifier.onTouchDown(scope.clock) { at, _ -> act(at) }
          SyncTask.DOUBLE_TAP ->
            Modifier.onTouchDown(scope.clock) { at, _ ->
              if (at - lastTap < 400) act(at) else lastTap = at
            }
          else ->
            Modifier.pointerInput(task) {
              detectVerticalDragGestures(onDragStart = { dragged = 0f }) { _, dy ->
                dragged += dy
                val up = task == SyncTask.SWIPE_UP && dragged < -threshold
                val down = task == SyncTask.SWIPE_DOWN && dragged > threshold
                if (up || down) act(scope.clock.hostNow())
              }
            }
        }
      )
  )
}

@Composable
private fun Acted(scope: StageScope<SyncState, SyncInput>, round: SyncRound) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(Space.s),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    round.tasks.keys.forEach { id ->
      val player = scope.players.firstOrNull { it.id.value == id } ?: return@forEach
      PlayerLight(
        player.name,
        player.light,
        size = 28.dp,
        dimmed = id !in scope.state.acted,
        seeker = player.seeker,
      )
    }
  }
}

@Composable
private fun Result(scope: StageScope<SyncState, SyncInput>, result: SyncResult) {
  val c = Theme.colors
  val landed = result.offsets.values.filterNotNull()
  val spread = if (landed.size >= 2) landed.max() - landed.min() else 0
  Column(
    Modifier.fillMaxSize().padding(horizontal = Space.gutter),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      if (result.passed) "In sync" else "Out of sync",
      style = Theme.type.display,
      color = if (result.passed) c.positive else c.negative,
    )
    Spacer(Modifier.height(Space.s))
    Text(
      if (result.passed) "Spread $spread ms" else culpritLine(scope, result),
      style = Theme.type.body,
      color = c.contentSecondary,
      textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(Space.x3l))
    Timeline(scope, result)
  }
}

private fun culpritLine(scope: StageScope<SyncState, SyncInput>, result: SyncResult): String {
  val who = scope.name(result.culprit)
  val offset = result.culprit?.let { result.offsets[it.value] }
  return when {
    offset == null -> "$who didn't make their move"
    offset < 0 -> "$who was ${-offset} ms early"
    else -> "$who was $offset ms late"
  }
}

@Composable
private fun Timeline(scope: StageScope<SyncState, SyncInput>, result: SyncResult) {
  val c = Theme.colors
  val range = 600f
  BoxWithConstraints(Modifier.fillMaxWidth().height(120.dp)) {
    val density = LocalDensity.current
    val w = with(density) { maxWidth.toPx() }
    Canvas(Modifier.fillMaxSize()) {
      val mid = size.width / 2
      val y = size.height / 2
      val band = result.window / range * (size.width / 2)
      drawRect(
        c.positive.copy(alpha = 0.1f),
        Offset(mid - band, y - 26.dp.toPx()),
        Size(band * 2, 52.dp.toPx()),
      )
      drawLine(c.lineStrong, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
      drawLine(
        c.content,
        Offset(mid, y - 30.dp.toPx()),
        Offset(mid, y + 30.dp.toPx()),
        2.dp.toPx(),
        StrokeCap.Round,
      )
    }
    result.offsets.entries.forEachIndexed { i, (id, offset) ->
      val player = scope.players.firstOrNull { it.id.value == id } ?: return@forEachIndexed
      val x =
        if (offset == null) w - 16f
        else w / 2 + (offset.coerceIn(-range.toInt(), range.toInt()) / range) * (w / 2 - 16f)
      val lane = if (i % 2 == 0) (-26).dp else 26.dp
      Column(
        Modifier.offset(with(density) { x.toDp() } - 14.dp, 60.dp - 14.dp + lane),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        PlayerLight(
          player.name,
          player.light,
          size = 28.dp,
          dimmed = offset == null || abs(offset) > result.window + 300,
        )
      }
    }
  }
}
