package xyz.mcxross.formation.ui.screens

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.BackHandler
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.resources.*
import xyz.mcxross.formation.ui.LocalGraph

// A full-screen story with no controls: tap the right half to go forward, the left half to go back,
// and hold anywhere to pause. It moves on by itself once the last chapter has played.
@Composable
internal fun OnboardingStory(playback: StoryPlayback, visible: Boolean, onNext: () -> Unit) {
  val graph = LocalGraph.current
  val c = Theme.colors
  val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
  val scope = rememberCoroutineScope()
  val durationScale = scope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
  val reduced = durationScale == 0f
  val chapter by remember(playback) { derivedStateOf { playback.chapter } }
  val finished by
    remember(playback) { derivedStateOf { playback.position == StoryTimeline.duration } }
  var held by remember { mutableStateOf(false) }
  val running =
    visible && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && playback.playing && !held && !reduced
  val headlines =
    listOf(
      Res.string.story_title_unlock,
      Res.string.story_title_people,
      Res.string.story_title_games,
      Res.string.story_title_play,
      Res.string.story_title_finish,
      Res.string.story_title_turn,
    )
  val bodies =
    listOf(
      Res.string.story_body_unlock,
      Res.string.story_body_people,
      Res.string.story_body_games,
      Res.string.story_body_play,
      Res.string.story_body_finish,
      Res.string.story_body_turn,
    )
  val last = headlines.lastIndex
  LaunchedEffect(running, durationScale) {
    if (!running) {
      graph.sounds.stop()
      return@LaunchedEffect
    }
    try {
      var previous = withFrameNanos { it }
      while (playback.playing) {
        val now = withFrameNanos { it }
        playback
          .advance((now - previous) / 1_000_000_000f / durationScale)
          .forEach(graph.sounds::play)
        previous = now
      }
    } finally {
      graph.sounds.stop()
    }
  }
  DisposableEffect(Unit) { onDispose { graph.sounds.stop() } }
  fun done() {
    playback.playing = false
    graph.sounds.stop()
    onNext()
  }
  fun seek(index: Int) {
    graph.sounds.stop()
    playback.seek(index.coerceIn(0, last), reduced)
  }
  fun forward() = if (chapter == last) done() else seek(chapter + 1)
  fun back() = seek(chapter - 1)
  // The last chapter's closing beat gets a moment on screen before the profile appears.
  if (finished && visible && !held)
    LaunchedEffect(Unit) {
      delay(FINAL_HOLD_MS)
      done()
    }
  BackHandler(enabled = visible && chapter > 0) { back() }
  val headline = stringResource(headlines[chapter])
  val body = stringResource(bodies[chapter])
  val nextLabel =
    stringResource(if (chapter == last) Res.string.action_get_started else Res.string.story_next)
  val previousLabel = stringResource(Res.string.story_previous)
  BoxWithConstraints(
    Modifier.fillMaxSize()
      .pointerInput(chapter, reduced) {
        detectTapGestures(
          onPress = {
            held = true
            try {
              tryAwaitRelease()
            } finally {
              held = false
            }
          },
          onTap = { at -> if (at.x < size.width / 2f) back() else forward() },
        )
      }
      .semantics {
        contentDescription = "$headline. $body"
        onClick(nextLabel) {
          forward()
          true
        }
        customActions =
          listOf(
            CustomAccessibilityAction(nextLabel) {
              forward()
              true
            },
            CustomAccessibilityAction(previousLabel) {
              back()
              true
            },
          )
      }
  ) {
    val stageHeight = (maxHeight * 0.62f).coerceIn(180.dp, 480.dp)
    Column(
      Modifier.fillMaxSize().padding(horizontal = Space.gutter),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Spacer(Modifier.weight(1f))
      OnboardingScene(
        time = { if (reduced) StoryTimeline.still(playback.chapter) else playback.position },
        reduced = reduced,
        modifier = Modifier.fillMaxWidth().height(stageHeight),
      )
      Column(
        Modifier.fillMaxWidth()
          .semantics { liveRegion = LiveRegionMode.Polite }
          .graphicsLayer {
            val p =
              if (reduced) 1f
              else ((playback.position - StoryTimeline.starts[chapter]) / .45f).coerceIn(0f, 1f)
            alpha = p
            translationY = (1 - p) * 10.dp.toPx()
          },
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(headline, style = Theme.type.title1, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.s))
        Text(
          body,
          style = Theme.type.body,
          color = c.contentSecondary,
          textAlign = TextAlign.Center,
        )
      }
      Spacer(Modifier.weight(1f))
    }
  }
}

private const val FINAL_HOLD_MS = 1_800L
