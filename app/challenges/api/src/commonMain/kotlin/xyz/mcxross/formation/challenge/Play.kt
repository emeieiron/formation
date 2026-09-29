package xyz.mcxross.formation.challenge

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.PlayerLight
import xyz.mcxross.formation.design.components.ProgressBar
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.tokens.Motion
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.sensors.Gesture
import xyz.mcxross.formation.sensors.MotionSense
import xyz.mcxross.formation.sensors.Pose
import xyz.mcxross.formation.session.ClockSync

// Refreshed every frame: read it in draw code to animate without recomposing.
@Composable
fun rememberHostNow(clock: ClockSync): State<Long> {
  val now = remember { mutableLongStateOf(clock.hostNow()) }
  LaunchedEffect(clock) { while (true) withFrameMillis { now.longValue = clock.hostNow() } }
  return now
}

@Composable
fun OnGesture(motion: MotionSense, enabled: Boolean = true, onGesture: (Gesture) -> Unit) {
  val handler by rememberUpdatedState(onGesture)
  if (enabled) LaunchedEffect(motion) { motion.gestures.collect { handler(it) } }
}

@Composable
fun rememberPose(motion: MotionSense): State<Pose> {
  val pose = remember { androidx.compose.runtime.mutableStateOf(motion.pose.value) }
  LaunchedEffect(motion) { motion.pose.collect { pose.value = it } }
  return pose
}

// Stamps the Seeker's time when the finger lands, not when it lifts.
fun Modifier.onTouchDown(
  clock: ClockSync,
  enabled: Boolean = true,
  onDown: (hostTime: Long, at: Offset) -> Unit,
): Modifier =
  if (!enabled) this
  else
    pointerInput(clock) {
      detectTapGestures(onPress = { offset -> onDown(clock.hostNow(), offset) })
    }

@Composable
fun Hud(
  label: String,
  progress: Float,
  modifier: Modifier = Modifier,
  lives: Int? = null,
  maxLives: Int = 3,
  accent: Color = Theme.colors.accent,
  trailing: String? = null,
) {
  val c = Theme.colors
  Column(modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.m)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        label,
        style = Theme.type.numeralSmall,
        color = c.content,
        modifier = Modifier.weight(1f),
        maxLines = 1,
      )
      trailing?.let {
        Text(it, style = Theme.type.footnote, color = c.contentSecondary, maxLines = 1)
      }
      if (lives != null) {
        Spacer(Modifier.width(Space.m))
        Lives(lives, maxLives)
      }
    }
    Spacer(Modifier.height(Space.s))
    ProgressBar(
      progress,
      brush = Brush.horizontalGradient(listOf(accent.copy(alpha = 0.7f), accent)),
      height = 5.dp,
    )
  }
}

@Composable
fun Lives(left: Int, total: Int, modifier: Modifier = Modifier) {
  val c = Theme.colors
  Row(
    modifier,
    horizontalArrangement = Arrangement.spacedBy(5.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    repeat(total) { i ->
      val on = i < left
      Box(
        Modifier.size(if (on) 10.dp else 8.dp)
          .clip(Shapes.circle)
          .background(if (on) c.negative else c.surfaceHigher)
      )
    }
  }
}

@Composable
fun ActionPrompt(
  icon: ImageVector,
  verb: String,
  color: Color,
  modifier: Modifier = Modifier,
  hint: String? = null,
  remaining: Float? = null,
  iconSize: Dp = 132.dp,
) {
  val c = Theme.colors
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
      Canvas(Modifier.fillMaxSize()) {
        drawCircle(
          Brush.radialGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent)),
          size.minDimension / 2,
        )
        val stroke = 5.dp.toPx()
        val arc = Size(size.width - stroke, size.height - stroke)
        drawArc(
          c.surfaceHigher,
          -90f,
          360f,
          false,
          Offset(stroke / 2, stroke / 2),
          arc,
          style = Stroke(stroke),
        )
        if (remaining != null) {
          drawArc(
            color,
            -90f,
            360f * remaining.coerceIn(0f, 1f),
            false,
            Offset(stroke / 2, stroke / 2),
            arc,
            style = Stroke(stroke, cap = StrokeCap.Round),
          )
        }
      }
      Icon(icon, null, tint = color, size = iconSize * 0.42f)
    }
    Spacer(Modifier.height(Space.l))
    Text(verb.uppercase(), style = Theme.type.hero, color = c.content, textAlign = TextAlign.Center)
    hint?.let {
      Spacer(Modifier.height(Space.s))
      Text(it, style = Theme.type.callout, color = c.contentSecondary, textAlign = TextAlign.Center)
    }
  }
}

@Composable
fun PlayerTag(player: PlayerView, modifier: Modifier = Modifier, you: Boolean = false) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    PlayerLight(player.name, player.light, size = 22.dp, seeker = player.seeker)
    Spacer(Modifier.width(6.dp))
    Text(if (you) "You" else player.name, style = Theme.type.subheadStrong, maxLines = 1)
  }
}

@Stable
class Flash {
  internal val level = Animatable(0f)
  internal var color: Color = Color.Transparent

  suspend fun fire(color: Color) {
    this.color = color
    level.snapTo(1f)
    level.animateTo(0f, tween(520, easing = Motion.standard))
  }
}

@Composable fun rememberFlash(): Flash = remember { Flash() }

@Composable
fun BoxScope.FlashLayer(flash: Flash) {
  Canvas(Modifier.matchParentSize()) {
    val level = flash.level.value
    if (level > 0f) {
      drawRect(
        Brush.radialGradient(
          listOf(Color.Transparent, flash.color.copy(alpha = 0.55f * level)),
          center,
          size.maxDimension * 0.75f,
        )
      )
    }
  }
}

@Composable
fun Countdown(
  goAt: Long,
  clock: ClockSync,
  modifier: Modifier = Modifier,
  go: String = "Go",
  onBeat: (Int) -> Unit = {},
) {
  val now by rememberHostNow(clock)
  val left = goAt - now
  val beat =
    when {
      left > 3_000 -> 4
      left > 0 -> ((left + 999) / 1_000).toInt()
      left > -700 -> 0
      else -> -1
    }
  val onBeatNow by rememberUpdatedState(onBeat)
  LaunchedEffect(beat) { if (beat in 0..3) onBeatNow(beat) }
  Box(modifier, contentAlignment = Alignment.Center) {
    AnimatedContent(
      beat,
      transitionSpec = {
        (scaleIn(Motion.bouncy(), initialScale = 1.6f) + fadeIn(tween(120))) togetherWith
          (scaleOut(tween(200), targetScale = 0.6f) + fadeOut(tween(200)))
      },
      label = "count",
    ) { b ->
      when {
        b in 1..3 ->
          Text(b.toString(), style = Theme.type.numeralHero, color = Theme.colors.content)
        b == 0 ->
          Text(
            go.uppercase(),
            style = Theme.type.hero,
            color = Theme.colors.content,
            modifier =
              Modifier.graphicsLayer {
                scaleX = 1.3f
                scaleY = 1.3f
              },
          )
        else -> Spacer(Modifier.size(1.dp))
      }
    }
  }
}
