package xyz.mcxross.formation.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.mcxross.formation.challenge.Challenge
import xyz.mcxross.formation.challenge.Countdown
import xyz.mcxross.formation.challenge.PlayerView
import xyz.mcxross.formation.challenge.StageScope
import xyz.mcxross.formation.challenge.StageAudio
import xyz.mcxross.formation.challenge.rememberHostNow
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.Chip
import xyz.mcxross.formation.design.components.IconButton
import xyz.mcxross.formation.design.components.IconButtonStyle
import xyz.mcxross.formation.design.components.Tag
import xyz.mcxross.formation.design.components.TagStyle
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Colors
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.design.tokens.Tone
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.sensors.Gesture
import xyz.mcxross.formation.sensors.Haptics
import xyz.mcxross.formation.sensors.MotionSense
import xyz.mcxross.formation.sensors.MotionSimulator
import xyz.mcxross.formation.sensors.Pose
import xyz.mcxross.formation.session.ClockSync
import xyz.mcxross.formation.session.FormationClient
import xyz.mcxross.formation.session.FormationJson
import xyz.mcxross.formation.session.ScreenProfile
import xyz.mcxross.formation.session.SessionSnapshot
import xyz.mcxross.formation.session.Stage
import xyz.mcxross.formation.session.ToPlayer
import xyz.mcxross.formation.platform.ScreenMeasurement
import xyz.mcxross.formation.state.ActiveSession
import xyz.mcxross.formation.ui.LocalGraph

@Composable
internal fun Play(
  session: ActiveSession,
  snapshot: SessionSnapshot,
  stage: Stage.Playing,
  me: PlayerId,
  onLeave: () -> Unit,
) {
  val graph = LocalGraph.current
  val c = Theme.colors
  val challenge = session.challenge
  val frame by session.client.frame.collectAsState()
  val now = rememberHostNow(session.client.sync)
  val counting by remember(stage.goAt) { derivedStateOf { now.value < stage.goAt } }
  // The countdown is gone by go, so the go beat is felt from here; a late arrival doesn't feel a stale one.
  LaunchedEffect(counting) {
    if (!counting && now.value - stage.goAt < GO_BEAT_MS) graph.platform.haptics.heavy()
  }
  val developer = graph.platform.config.developer
  val autoplay by graph.autoplay.collectAsState()
  if (challenge == null) {
    Problem(
      "Update to play",
      "This Formation uses a challenge this version of the app doesn't have yet.",
      onBack = onLeave,
    )
    return
  }
  if (challenge.fullScreen) {
    DisposableEffect(Unit) {
      graph.platform.screen.fullScreen(true)
      onDispose { graph.platform.screen.fullScreen(false) }
    }
  }
  Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
      // A full-screen stage leaves through the system back gesture instead.
      if (!challenge.fullScreen) SessionBar(
        challenge.info.title,
        challenge.info.light,
        session.client.sync.rttMs,
        autoplay && developer,
        onLeave,
      )
      Box(Modifier.weight(1f).fillMaxWidth()) {
        val current = frame
        if (current != null && current.round == snapshot.round) {
          @Suppress("UNCHECKED_CAST")
          StageHost(
            challenge as Challenge<Any, Any>,
            current,
            snapshot,
            me,
            session.client,
            graph.motion,
            graph.platform.haptics,
            graph.challengeAudio,
            (graph.platform.screen.measurement.value as? ScreenMeasurement.Measured)?.profile,
            c,
            autoplay = autoplay && developer,
          )
        }
      }
    }
    // The countdown leaves at go without fading, so nothing is drawn over the first wave's launch.
    AnimatedVisibility(counting, enter = fadeIn(), exit = ExitTransition.None) {
      Box(
        Modifier.fillMaxSize().drawBehind { drawRect(c.background.copy(alpha = 0.96f)) },
        contentAlignment = Alignment.Center,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
              challenge.info.title.uppercase(),
              style = Theme.type.overline,
              color = c.light(challenge.info.light).color,
            )
            Spacer(Modifier.height(Space.s))
            Text("Get ready", style = Theme.type.title2, color = c.contentSecondary)
          }
          Spacer(Modifier.height(Space.xl))
          Countdown(
            stage.goAt,
            session.client.sync,
            Modifier.height(140.dp),
            go = null,
            onBeat = { beat -> if (beat > 0) graph.platform.haptics.tick() },
          )
        }
      }
    }
    if (developer && graph.simulator != null) {
      MotionPad(
        graph.simulator,
        autoplay = autoplay,
        onAutoplay = { graph.autoplay.value = it },
        modifier = Modifier.align(Alignment.BottomStart),
      )
    }
  }
}

@Composable
private fun SessionBar(
  title: String,
  light: Int,
  rttMs: Long?,
  autoplay: Boolean,
  onLeave: () -> Unit,
) {
  val c = Theme.colors
  Row(
    Modifier.fillMaxWidth().height(44.dp).padding(horizontal = Space.xs),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(
      Icons.Close,
      "Leave",
      onLeave,
      style = IconButtonStyle.Ghost,
      size = 40.dp,
      tint = c.contentTertiary,
    )
    Row(
      Modifier.weight(1f),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        title.uppercase(),
        style = Theme.type.overline,
        color = c.light(light).color,
        textAlign = TextAlign.Center,
      )
      if (autoplay) {
        Spacer(Modifier.width(Space.s))
        Tag("Autoplay", tone = Tone.Warning, style = TagStyle.Solid)
      }
    }
    Row(
      Modifier.width(72.dp).padding(end = Space.s),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.End,
    ) {
      if (rttMs != null) {
        Icon(
          Icons.Signal,
          null,
          tint = if (rttMs < 60) c.positive else if (rttMs < 150) c.warning else c.negative,
          size = 14.dp,
        )
        Spacer(Modifier.width(4.dp))
        Text("$rttMs ms", style = Theme.type.caption, color = c.contentTertiary, maxLines = 1)
      }
    }
  }
}

@Composable
private fun <S : Any, I : Any> StageHost(
  challenge: Challenge<S, I>,
  frame: ToPlayer.Frame,
  snapshot: SessionSnapshot,
  me: PlayerId,
  client: FormationClient,
  motion: MotionSense,
  haptics: Haptics,
  audio: StageAudio,
  screen: ScreenProfile?,
  colors: Colors,
  autoplay: Boolean,
) {
  val scope =
    remember(challenge, snapshot.round) {
      LiveStage(challenge, client, me, client.sync, motion, haptics, audio, screen)
    }
  val decoded =
    remember(frame) {
      runCatching { FormationJson.decodeFromJsonElement(challenge.stateSerializer, frame.state) }
        .getOrNull()
    }
  val players =
    remember(snapshot.players) {
      snapshot.players.map {
        PlayerView(it.id, it.name, colors.light(it.light), it.seeker, it.connected)
      }
    }
  SideEffect {
    decoded?.let { scope.current = it }
    scope.players = players
    scope.round = snapshot.round
  }
  if (autoplay) {
    LaunchedEffect(scope) {
      val sent = HashSet<String>()
      while (true) {
        val state = scope.current
        if (state != null) {
          challenge.autopilot(state, me, client.sync.hostNow())?.let { move ->
            if (sent.add(move.key)) scope.send(move.input)
          }
        }
        delay(AUTOPILOT_MS)
      }
    }
  }
  if (decoded != null || scope.current != null) {
    if (scope.current == null) scope.current = decoded
    challenge.Stage(scope)
  }
}

private const val AUTOPILOT_MS = 40L
private const val GO_BEAT_MS = 700L

@Stable
private class LiveStage<S : Any, I : Any>(
  private val challenge: Challenge<S, I>,
  private val client: FormationClient,
  override val me: PlayerId,
  override val clock: ClockSync,
  override val motion: MotionSense,
  override val haptics: Haptics,
  override val audio: StageAudio,
  override val screen: ScreenProfile?,
) : StageScope<S, I> {
  var current by mutableStateOf<S?>(null)
  override var players by mutableStateOf(emptyList<PlayerView>())
  override var round by mutableIntStateOf(0)

  override val state: S
    get() = current ?: error("No state yet")

  override fun send(input: I) =
    client.play(FormationJson.encodeToJsonElement(challenge.inputSerializer, input))
}

@Composable
private fun MotionPad(
  simulator: MotionSimulator,
  autoplay: Boolean,
  onAutoplay: (Boolean) -> Unit,
  modifier: Modifier,
) {
  val c = Theme.colors
  var open by remember { mutableStateOf(false) }
  var held by remember { mutableStateOf<String?>(null) }
  Column(modifier.navigationBarsPadding().padding(Space.s)) {
    if (open) {
      Row(
        Modifier.clip(Shapes.control)
          .background(c.surfaceHigher.copy(alpha = 0.96f))
          .horizontalScroll(rememberScrollState())
          .padding(Space.s),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Chip("Autoplay", autoplay, { onAutoplay(!autoplay) }, accent = c.warning)
        Chip("Shake", false, { simulator.perform(Gesture.SHAKE) })
        Chip("Swing", false, { simulator.perform(Gesture.SWING) })
        Chip("Cover", false, { simulator.perform(Gesture.COVER) })
        listOf(
            "Up" to Pose.FACE_UP,
            "Down" to Pose.FACE_DOWN,
            "Upright" to Pose.UPRIGHT,
            "Upside" to Pose.UPSIDE_DOWN,
            "Side L" to Pose.SIDEWAYS_LEFT,
            "Side R" to Pose.SIDEWAYS_RIGHT,
          )
          .forEach { (label, pose) ->
            Chip(
              label,
              held == label,
              {
                held = label
                simulator.hold(pose)
              },
            )
          }
        Chip(
          "Lean ◀",
          held == "L",
          {
            held = "L"
            simulator.lean(-28f)
          },
        )
        Chip(
          "Lean ▶",
          held == "R",
          {
            held = "R"
            simulator.lean(28f)
          },
        )
        Chip(
          "Sensors",
          held == null,
          {
            held = null
            simulator.release()
          },
        )
      }
      Spacer(Modifier.height(Space.xs))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconButton(
        Icons.Tilt,
        "Motion pad",
        { open = !open },
        style = if (open) IconButtonStyle.Inverse else IconButtonStyle.Filled,
        size = 40.dp,
      )
      if (!open)
        Text(
          "  Motion pad",
          style = Theme.type.caption,
          color = c.contentTertiary,
          textAlign = TextAlign.Start,
        )
    }
  }
}
