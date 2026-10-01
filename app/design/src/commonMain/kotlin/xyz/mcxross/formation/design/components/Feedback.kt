package xyz.mcxross.formation.design.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.mcxross.formation.design.LocalContentColor
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Panel
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.foundation.pressable
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Motion
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Sizes
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.design.tokens.Tone

@Composable
fun Spinner(size: Dp = 20.dp, color: Color = LocalContentColor.current, strokeWidth: Dp = 2.dp) {
  val motion = rememberInfiniteTransition(label = "spinner")
  val rotation by
    motion.animateFloat(
      0f,
      360f,
      infiniteRepeatable(tween(850, easing = LinearEasing)),
      label = "rotation",
    )
  val sweep by
    motion.animateFloat(
      60f,
      260f,
      infiniteRepeatable(tween(1_100, easing = Motion.standard), RepeatMode.Reverse),
      label = "sweep",
    )
  Canvas(
    Modifier.size(size).semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
  ) {
    val stroke = strokeWidth.toPx()
    rotate(rotation) {
      drawArc(
        color,
        -90f,
        sweep,
        false,
        Offset(stroke / 2, stroke / 2),
        Size(this.size.width - stroke, this.size.height - stroke),
        style = Stroke(stroke, cap = StrokeCap.Round),
      )
    }
  }
}

@Composable
fun ProgressBar(
  progress: Float,
  modifier: Modifier = Modifier,
  brush: Brush? = null,
  color: Color = Theme.colors.content,
  track: Color = Theme.colors.surfaceHigher,
  height: Dp = 6.dp,
) {
  val value by
    animateFloatAsState(progress.coerceIn(0f, 1f), Motion.emphasized(600), label = "progress")
  Box(
    modifier.fillMaxWidth().height(height).clip(Shapes.pill).background(track).semantics {
      progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
    }
  ) {
    Box(
      Modifier.fillMaxWidth(value)
        .fillMaxHeight()
        .clip(Shapes.pill)
        .then(if (brush != null) Modifier.background(brush) else Modifier.background(color))
    )
  }
}

@Composable
fun ProgressRing(
  progress: Float,
  modifier: Modifier = Modifier,
  stroke: Dp = 6.dp,
  color: Color = Theme.colors.content,
  track: Color = Theme.colors.surfaceHigher,
  animate: Boolean = true,
  content: @Composable BoxScope.() -> Unit = {},
) {
  val target = progress.coerceIn(0f, 1f)
  val value by
    animateFloatAsState(target, if (animate) Motion.emphasized(600) else tween(0), label = "ring")
  Box(modifier, contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxSize()) {
      val s = stroke.toPx()
      val arc = Size(size.width - s, size.height - s)
      val topLeft = Offset(s / 2, s / 2)
      drawArc(track, -90f, 360f, false, topLeft, arc, style = Stroke(s))
      if (value > 0f)
        drawArc(
          color,
          -90f,
          360f * value,
          false,
          topLeft,
          arc,
          style = Stroke(s, cap = StrokeCap.Round),
        )
    }
    content()
  }
}

@Composable
fun ReadyDots(ready: List<Color?>, modifier: Modifier = Modifier, size: Dp = 10.dp) {
  val c = Theme.colors
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    ready.forEach { color ->
      val fill by animateColorAsState(color ?: c.surfaceHigher, Motion.standard(), label = "dot")
      Box(Modifier.size(size).clip(Shapes.circle).background(fill))
    }
  }
}

@Composable
fun Notice(
  text: String,
  modifier: Modifier = Modifier,
  tone: Tone = Tone.Neutral,
  icon: ImageVector? = defaultIcon(tone),
  title: String? = null,
  action: String? = null,
  onAction: (() -> Unit)? = null,
) {
  val c = Theme.colors
  val tint = if (tone == Tone.Neutral) c.content else c.tone(tone)
  Panel(
    modifier.fillMaxWidth(),
    shape = Shapes.control,
  ) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = 14.dp),
      verticalAlignment = if (title == null) Alignment.CenterVertically else Alignment.Top,
    ) {
      icon?.let {
        Icon(it, null, tint = tint, size = Sizes.iconM)
        Spacer(Modifier.width(Space.m))
      }
      Column(Modifier.weight(1f)) {
        title?.let { Text(it, style = Theme.type.subheadStrong) }
        Text(
          text,
          style = Theme.type.subhead,
          color = if (title == null) c.content else c.contentSecondary,
        )
      }
      if (action != null && onAction != null)
        TextButton(action, onAction, Modifier.padding(start = Space.s), tone = tone)
    }
  }
}

private fun defaultIcon(tone: Tone): ImageVector =
  when (tone) {
    Tone.Positive -> Icons.Check
    Tone.Warning,
    Tone.Negative -> Icons.Alert
    Tone.Reward -> Icons.Coin
    else -> Icons.Info
  }

@Composable
fun EmptyState(
  title: String,
  body: String,
  modifier: Modifier = Modifier,
  art: (@Composable () -> Unit)? = null,
  action: String? = null,
  onAction: (() -> Unit)? = null,
) {
  Column(
    modifier.fillMaxWidth().padding(horizontal = Space.x3l, vertical = Space.x3l),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    art?.let {
      it()
      Spacer(Modifier.height(Space.xl))
    }
    Text(title, style = Theme.type.title3, textAlign = TextAlign.Center)
    Spacer(Modifier.height(Space.s))
    Text(
      body,
      style = Theme.type.subhead,
      color = Theme.colors.contentSecondary,
      textAlign = TextAlign.Center,
      modifier = Modifier.widthIn(max = 320.dp),
    )
    if (action != null && onAction != null) {
      Spacer(Modifier.height(Space.xxl))
      Button(action, onAction, style = ButtonStyle.Secondary, size = ButtonSize.Medium)
    }
  }
}

@Stable
class Toaster {
  internal var current by mutableStateOf<Toast?>(null)
    private set

  private var counter = 0L

  fun show(message: String, tone: Tone = Tone.Neutral, icon: ImageVector? = null) {
    current = Toast(counter++, message, tone, icon)
  }

  internal fun dismiss(id: Long) {
    if (current?.id == id) current = null
  }
}

internal class Toast(val id: Long, val message: String, val tone: Tone, val icon: ImageVector?)

val LocalToaster = staticCompositionLocalOf { Toaster() }

@Composable
internal fun ToastLayer(toaster: Toaster, modifier: Modifier = Modifier) {
  val c = Theme.colors
  AnimatedContent(
    toaster.current,
    modifier
      .fillMaxWidth()
      .windowInsetsPadding(WindowInsets.statusBars)
      .padding(horizontal = Space.l, vertical = Space.s),
    transitionSpec = {
      (slideInVertically(Motion.emphasized()) { -it } + fadeIn(Motion.standard())) togetherWith
        (slideOutVertically(Motion.exit()) { -it } + fadeOut(Motion.exit()))
    },
    contentAlignment = Alignment.TopCenter,
    label = "toast",
  ) { toast ->
    if (toast == null) return@AnimatedContent Spacer(Modifier.fillMaxWidth())
    LaunchedEffect(toast.id) {
      delay(3_000)
      toaster.dismiss(toast.id)
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
      Row(
        Modifier.widthIn(max = 480.dp)
          .pressable({ toaster.dismiss(toast.id) }, shape = Shapes.pill, role = null)
          .background(c.surfaceHigher)
          .padding(horizontal = Space.l, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val icon = toast.icon ?: if (toast.tone == Tone.Neutral) null else defaultIcon(toast.tone)
        icon?.let {
          Icon(
            it,
            null,
            tint = if (toast.tone == Tone.Neutral) c.content else c.tone(toast.tone),
            size = Sizes.iconM,
          )
          Spacer(Modifier.width(Space.s))
        }
        Text(toast.message, style = Theme.type.subheadStrong, color = c.content)
      }
    }
  }
}
