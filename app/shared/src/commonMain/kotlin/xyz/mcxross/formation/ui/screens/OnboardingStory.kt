package xyz.mcxross.formation.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.jetbrains.compose.resources.stringResource
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.*
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.foundation.pressable
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.resources.*
import xyz.mcxross.formation.ui.LocalGraph

@Composable
internal fun OnboardingStory(playback: StoryPlayback, visible: Boolean, onNext: () -> Unit) {
  val graph = LocalGraph.current
  val c = Theme.colors
  val fontScale = LocalDensity.current.fontScale
  val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
  val sounds by graph.sounds.enabled.collectAsState()
  val scope = rememberCoroutineScope()
  val durationScale = scope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
  val reduced = durationScale == 0f
  val chapter by remember(playback) { derivedStateOf { playback.chapter } }
  val finished by remember(playback) { derivedStateOf { playback.position == StoryTimeline.duration } }
  val running = visible && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && playback.playing && !reduced
  val labels = listOf(
    Res.string.story_chapter_unlock, Res.string.story_chapter_people, Res.string.story_chapter_games,
    Res.string.story_chapter_play, Res.string.story_chapter_finish, Res.string.story_chapter_turn,
  ).map { stringResource(it) }
  val headlines = listOf(
    Res.string.story_title_unlock, Res.string.story_title_people, Res.string.story_title_games,
    Res.string.story_title_play, Res.string.story_title_finish, Res.string.story_title_turn,
  )
  val bodies = listOf(
    Res.string.story_body_unlock, Res.string.story_body_people, Res.string.story_body_games,
    Res.string.story_body_play, Res.string.story_body_finish, Res.string.story_body_turn,
  )
  LaunchedEffect(running, durationScale) {
    if (!running) { graph.sounds.stop(); return@LaunchedEffect }
    try {
      var previous = withFrameNanos { it }
      while (playback.playing) {
        val now = withFrameNanos { it }
        playback.advance((now - previous) / 1_000_000_000f / durationScale).forEach(graph.sounds::play)
        previous = now
      }
    } finally { graph.sounds.stop() }
  }
  DisposableEffect(Unit) { onDispose { graph.sounds.stop() } }
  fun seek(index: Int) {
    graph.sounds.stop()
    playback.seek(index, reduced)
  }
  fun done() { playback.playing = false; graph.sounds.stop(); onNext() }
  Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
      Text("Formation", Modifier.weight(1f), style = Theme.type.title3, color = c.contentSecondary)
      IconButton(Icons.Sound, stringResource(if (sounds) Res.string.story_mute else Res.string.story_unmute),
        { graph.sounds.setEnabled(!sounds) }, style = IconButtonStyle.Ghost,
        tint = if (sounds) c.content else c.contentTertiary)
      TextButton(stringResource(Res.string.story_skip), ::done)
    }
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
      val stageHeight = (maxHeight - 44.dp - 150.dp * fontScale).coerceIn(150.dp, 420.dp)
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          labels.forEachIndexed { i, label ->
            Box(Modifier.weight(1f).height(44.dp).pressable({ seek(i) }, shape = Shapes.control,
              onClickLabel = label).semantics { contentDescription = label; selected = chapter == i }, contentAlignment = Alignment.Center) {
              Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                drawLine(c.lineStrong, Offset.Zero, Offset(size.width, 0f), size.height)
                val end = StoryTimeline.starts.getOrNull(i + 1) ?: StoryTimeline.duration
                val p = if (reduced) (if (i <= chapter) 1f else 0f)
                  else ((playback.position - StoryTimeline.starts[i]) / (end - StoryTimeline.starts[i])).coerceIn(0f, 1f)
                if (p > 0) drawLine(c.accent, Offset.Zero, Offset(size.width * p, 0f), size.height)
              }
            }
          }
        }
        Text("${chapter + 1} / 6 · ${labels[chapter]}", style = Theme.type.overline, color = c.contentTertiary)
        OnboardingScene(
          time = { if (reduced) StoryTimeline.still(playback.chapter) else playback.position },
          reduced = reduced,
          modifier = Modifier.fillMaxWidth().height(stageHeight),
        )
        Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.graphicsLayer {
          val p = if (reduced) 1f else ((playback.position - StoryTimeline.starts[chapter]) / .45f).coerceIn(0f, 1f)
          alpha = p
          translationY = (1 - p) * 10.dp.toPx()
        }, horizontalAlignment = Alignment.CenterHorizontally) {
          Text(stringResource(headlines[chapter]), style = Theme.type.title1, textAlign = TextAlign.Center)
          Spacer(Modifier.height(Space.s))
          Text(stringResource(bodies[chapter]), style = Theme.type.body, color = c.contentSecondary, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(Space.l))
      }
    }
    BottomActions {
      Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        if (!reduced) Button(
          stringResource(if (finished) Res.string.story_replay
            else if (playback.playing) Res.string.story_pause else Res.string.story_resume),
          { if (finished) seek(0) else playback.playing = !playback.playing },
          Modifier.weight(1f), style = ButtonStyle.Secondary)
        Button(stringResource(if (chapter == 5) Res.string.action_get_started else Res.string.story_next),
          { if (chapter == 5) done() else seek(chapter + 1) }, Modifier.weight(1f), trailingIcon = Icons.ArrowRight)
      }
    }
  }
}
