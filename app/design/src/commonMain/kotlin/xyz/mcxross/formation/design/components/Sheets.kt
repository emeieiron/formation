package xyz.mcxross.formation.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlin.math.roundToInt
import xyz.mcxross.formation.design.LocalRaised
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.tokens.Motion
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Sizes
import xyz.mcxross.formation.design.tokens.Space

@Stable
class OverlayController internal constructor() {
  internal val entries = mutableStateListOf<SheetEntry>()

  val hasOpenSheet: Boolean
    get() = entries.any { it.attached }
}

@Stable
internal class SheetEntry(
  content: @Composable ColumnScope.() -> Unit,
  onDismiss: () -> Unit,
  dismissible: Boolean,
  locals: CompositionLocalContext,
) {
  var content by mutableStateOf(content)
  var onDismiss by mutableStateOf(onDismiss)
  var dismissible by mutableStateOf(dismissible)
  var locals by mutableStateOf(locals)
  var attached by mutableStateOf(false)
}

val LocalOverlay = staticCompositionLocalOf<OverlayController?> { null }

@Composable
fun OverlayHost(
  modifier: Modifier = Modifier,
  toaster: Toaster = remember { Toaster() },
  content: @Composable () -> Unit,
) {
  val controller = remember { OverlayController() }
  CompositionLocalProvider(LocalOverlay provides controller, LocalToaster provides toaster) {
    Box(modifier.fillMaxSize()) {
      content()
      controller.entries.forEach { entry -> key(entry) { SheetLayer(entry, controller) } }
      ToastLayer(toaster, Modifier.align(Alignment.TopCenter))
    }
  }
}

@Composable
fun ModalSheet(
  onDismiss: () -> Unit,
  dismissible: Boolean = true,
  content: @Composable ColumnScope.() -> Unit,
) {
  val overlay = LocalOverlay.current ?: error("ModalSheet must be inside an OverlayHost")
  val locals = currentCompositionLocalContext
  val entry = remember { SheetEntry(content, onDismiss, dismissible, locals) }
  SideEffect {
    entry.content = content
    entry.onDismiss = onDismiss
    entry.dismissible = dismissible
    entry.locals = locals
  }
  DisposableEffect(overlay, entry) {
    entry.attached = true
    if (entry !in overlay.entries) overlay.entries.add(entry)
    onDispose { entry.attached = false }
  }
}

@Composable
private fun SheetLayer(entry: SheetEntry, controller: OverlayController) {
  val c = Theme.colors
  val visibility = remember { MutableTransitionState(false) }
  SideEffect { visibility.targetState = entry.attached }
  if (!entry.attached && visibility.isIdle && !visibility.currentState) {
    LaunchedEffect(Unit) { controller.entries.remove(entry) }
  }
  NavigationBackHandler(
    rememberNavigationEventState(NavigationEventInfo.None),
    isBackEnabled = entry.attached,
  ) {
    if (entry.dismissible) entry.onDismiss()
  }
  BoxWithConstraints(Modifier.fillMaxSize()) {
    AnimatedVisibility(
      visibility,
      enter = fadeIn(Motion.standard()),
      exit = fadeOut(Motion.standard(Motion.BASE)),
    ) {
      Box(
        Modifier.fillMaxSize().background(c.scrim).clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
        ) {
          if (entry.dismissible) entry.onDismiss()
        }
      )
    }
    AnimatedVisibility(
      visibility,
      Modifier.align(Alignment.BottomCenter),
      enter =
        slideInVertically(Motion.emphasized(260)) { (it / 8).coerceAtMost(84) } +
          fadeIn(Motion.standard(260)),
      exit = slideOutVertically(Motion.exit()) { it / 8 } + fadeOut(Motion.exit()),
    ) {
      CompositionLocalProvider(entry.locals) {
        SheetPanel(entry, Modifier.heightIn(max = maxHeight - 48.dp))
      }
    }
  }
}

@Composable
private fun SheetPanel(entry: SheetEntry, modifier: Modifier) {
  val c = Theme.colors
  var drag by remember { mutableFloatStateOf(0f) }
  var height by remember { mutableIntStateOf(0) }
  Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
    Column(
      modifier
        .widthIn(max = 640.dp)
        .fillMaxWidth()
        .onSizeChanged { height = it.height }
        .offset { IntOffset(0, drag.roundToInt()) }
        .clip(Shapes.sheet)
        .background(c.surface)
        .border(Sizes.hairline, c.line, Shapes.sheet)
        .drawBehind { drawLine(c.accent, Offset(0f, 0f), Offset(size.width, 0f), 4.dp.toPx()) }
        .draggable(
          rememberDraggableState { delta -> drag = (drag + delta).coerceAtLeast(0f) },
          Orientation.Vertical,
          enabled = entry.dismissible,
          onDragStopped = { velocity ->
            if (drag > height * 0.28f || velocity > 1_600f) entry.onDismiss()
            else
              animate(drag, 0f, velocity, spring(dampingRatio = 0.9f, stiffness = 700f)) { value, _
                ->
                drag = value
              }
          },
        )
        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
        .padding(bottom = Space.l)
    ) {
      Grabber()
      CompositionLocalProvider(LocalRaised provides true) { entry.content(this) }
    }
  }
}

@Composable
fun Grabber(modifier: Modifier = Modifier) {
  Box(
    modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      Modifier.size(width = 36.dp, height = 4.dp)
        .clip(Shapes.pill)
        .background(Theme.colors.lineStrong)
    )
  }
}

@Composable
fun SheetHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
  Column(
    modifier
      .fillMaxWidth()
      .padding(start = Space.xxl, end = Space.xxl, top = Space.m, bottom = Space.l)
  ) {
    Text(title.uppercase(), style = Theme.type.title2)
    subtitle?.let {
      Spacer(Modifier.height(Space.s))
      Text(it, style = Theme.type.body, color = Theme.colors.contentSecondary)
    }
  }
}

@Composable
fun ColumnScope.SheetActions(content: @Composable ColumnScope.() -> Unit) {
  Column(
    Modifier.fillMaxWidth().padding(start = Space.xxl, end = Space.xxl, top = Space.xl),
    verticalArrangement = Arrangement.spacedBy(Space.m),
    content = content,
  )
}

@Composable
fun ConfirmSheet(
  title: String,
  body: String,
  confirm: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  style: ButtonStyle = ButtonStyle.Primary,
  cancel: String = "Cancel",
  loading: Boolean = false,
) {
  ModalSheet(onDismiss, dismissible = !loading) {
    SheetHeader(title, subtitle = body)
    SheetActions {
      Button(confirm, onConfirm, style = style, loading = loading)
      Button(cancel, onDismiss, style = ButtonStyle.Ghost, enabled = !loading)
    }
  }
}
