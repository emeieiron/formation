package xyz.mcxross.formation.ui.session

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.BackHandler
import xyz.mcxross.formation.design.components.BottomActions
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.components.ButtonStyle
import xyz.mcxross.formation.design.components.ConfirmSheet
import xyz.mcxross.formation.design.components.Page
import xyz.mcxross.formation.design.components.Spinner
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Motion
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.FormationClient
import xyz.mcxross.formation.session.SessionSnapshot
import xyz.mcxross.formation.session.Stage
import xyz.mcxross.formation.state.ActiveSession
import xyz.mcxross.formation.ui.LocalGraph
import xyz.mcxross.formation.ui.nav.Screen

@Composable
fun SessionScreen() {
  val graph = LocalGraph.current
  val session by graph.session.collectAsState()
  val active = session
  if (active == null) {
    LaunchedEffect(Unit) { graph.navigator.remove(Screen.Session) }
    return
  }
  val status by active.status.collectAsState()
  val snapshot by active.snapshot.collectAsState()
  val me by active.client.me.collectAsState()
  var confirmLeave by remember { mutableStateOf(false) }

  DisposableEffect(active) {
    graph.platform.external.keepScreenOn(true)
    onDispose { graph.platform.external.keepScreenOn(false) }
  }

  fun leave() {
    confirmLeave = false
    graph.endSession()
    graph.navigator.remove(Screen.Session)
  }

  val ended = status is FormationClient.Status.Ended || status is FormationClient.Status.Rejected
  BackHandler { if (ended) leave() else confirmLeave = true }

  Page {
    Box(Modifier.fillMaxSize()) {
      when (val st = status) {
        is FormationClient.Status.Rejected ->
          Problem("Couldn't join", st.reason.message, onBack = ::leave)
        is FormationClient.Status.Ended ->
          Problem("The Formation ended", st.reason, onBack = ::leave)
        else -> {
          val s = snapshot
          if (s == null || me == null) Connecting(st, onCancel = ::leave)
          else Phases(active, s, me!!, onLeave = { confirmLeave = true }, onDone = ::leave)
        }
      }
      ReconnectingBanner(
        status is FormationClient.Status.Reconnecting && snapshot != null,
        Modifier.align(Alignment.TopCenter),
      )
    }
  }

  if (confirmLeave) {
    ConfirmSheet(
      title = if (active.isHost) "End this Formation?" else "Leave this Formation?",
      body =
        if ((snapshot?.stage as? Stage.Won)?.storageProblem != null)
          "This win has not been saved yet. Leaving may lose the result."
        else if (snapshot?.stage is Stage.Won)
          "The win is saved. Resume from Home if the group still needs to finish sealing it."
        else if (active.isHost)
          "Everyone will be sent home and the reward stays locked for another time."
        else "The group can't finish without you unless the Seeker finds someone else.",
      confirm = if (active.isHost) "End Formation" else "Leave",
      onConfirm = ::leave,
      onDismiss = { confirmLeave = false },
      style = ButtonStyle.Destructive,
    )
  }
}

@Composable
private fun ReconnectingBanner(visible: Boolean, modifier: Modifier) {
  AnimatedVisibility(
    visible,
    modifier,
    enter = slideInVertically { -it } + fadeIn(),
    exit = slideOutVertically { -it } + fadeOut(),
  ) {
    Row(
      Modifier.padding(Space.s)
        .clip(Shapes.pill)
        .background(Theme.colors.warning)
        .padding(horizontal = Space.l, vertical = Space.s),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Spinner(14.dp, color = Theme.colors.onInverse)
      Spacer(Modifier.width(Space.s))
      Text(
        "Reconnecting to the Seeker…",
        style = Theme.type.subheadStrong,
        color = Theme.colors.onInverse,
      )
    }
  }
}

@Composable
private fun Phases(
  session: ActiveSession,
  snapshot: SessionSnapshot,
  me: PlayerId,
  onLeave: () -> Unit,
  onDone: () -> Unit,
) {
  val shown = rememberFinale(snapshot)
  AnimatedContent(
    targetState = shown.stage::class.simpleName + shown.round,
    transitionSpec = { (fadeIn(Motion.standard()) + slideInVertically(Motion.standard()) { it / 32 }) togetherWith fadeOut(Motion.exit()) },
    label = "phase",
  ) { _ ->
    when (val stage = shown.stage) {
      Stage.Lobby -> Lobby(session, shown, me, onLeave)
      is Stage.Briefing -> Briefing(session, shown, stage, me, onLeave)
      is Stage.Playing -> Play(session, shown, stage, me, onLeave)
      is Stage.Won -> Won(session, shown, stage, me, onDone)
      is Stage.Lost -> Lost(session, shown, stage, me, onLeave)
      is Stage.Closed -> Problem("The Formation ended", stage.reason, onBack = onDone)
    }
  }
}

// A round that just ended stays on its stage briefly so the game can play out its final moment.
@Composable
private fun rememberFinale(snapshot: SessionSnapshot): SessionSnapshot {
  var playing by remember { mutableStateOf<SessionSnapshot?>(null) }
  var released by remember { mutableIntStateOf(-1) }
  val last = playing
  val ending = last != null && last.round == snapshot.round && released != snapshot.round &&
    (snapshot.stage is Stage.Won || snapshot.stage is Stage.Lost)
  SideEffect { if (snapshot.stage is Stage.Playing) playing = snapshot }
  if (ending) {
    LaunchedEffect(snapshot.round) {
      delay(FINALE_MS)
      released = snapshot.round
    }
  }
  return if (ending) last!! else snapshot
}

private const val FINALE_MS = 1_200L

@Composable
private fun Connecting(status: FormationClient.Status, onCancel: () -> Unit) {
  val c = Theme.colors
  Column(Modifier.fillMaxSize()) {
    Column(
      Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.gutter),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Spacer(Modifier.weight(1f))
      Spinner(28.dp, color = c.contentSecondary)
      Spacer(Modifier.height(Space.xl))
      Text(
        if (status is FormationClient.Status.Reconnecting) "Finding the Seeker again…"
        else "Joining the Formation…",
        style = Theme.type.title2,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(Space.s))
      Text(
        "Stay close to the Seeker and on the same Wi-Fi.",
        style = Theme.type.body,
        color = c.contentSecondary,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.weight(1.2f))
    }
    BottomActions { Button("Cancel", onCancel, style = ButtonStyle.Ghost) }
  }
}

@Composable
internal fun Problem(title: String, body: String, onBack: () -> Unit) {
  val c = Theme.colors
  Column(Modifier.fillMaxSize()) {
    Column(
      Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.gutter),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Spacer(Modifier.weight(1f))
      Icon(Icons.Alert, null, tint = c.warning, size = 48.dp)
      Spacer(Modifier.height(Space.l))
      Text(title, style = Theme.type.title1, textAlign = TextAlign.Center)
      Spacer(Modifier.height(Space.s))
      Text(body, style = Theme.type.body, color = c.contentSecondary, textAlign = TextAlign.Center)
      Spacer(Modifier.weight(1.2f))
    }
    BottomActions { Button("Back home", onBack) }
  }
}
