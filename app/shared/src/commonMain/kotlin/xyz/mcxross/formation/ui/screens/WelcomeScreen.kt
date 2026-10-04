package xyz.mcxross.formation.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import xyz.mcxross.formation.resources.*
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.BackHandler
import xyz.mcxross.formation.design.components.BottomActions
import xyz.mcxross.formation.design.components.LiveryRule
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.components.ButtonStyle
import xyz.mcxross.formation.design.components.Notice
import xyz.mcxross.formation.design.components.Page
import xyz.mcxross.formation.design.components.PlayerLight
import xyz.mcxross.formation.design.components.TextField
import xyz.mcxross.formation.design.components.TopBar
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Motion
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Sizes
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.design.tokens.Tone
import xyz.mcxross.formation.state.Profile
import xyz.mcxross.formation.state.SeekerStatus
import xyz.mcxross.formation.ui.LocalGraph

@Composable
fun WelcomeScreen(onDone: (Profile) -> Unit) {
  val graph = LocalGraph.current
  var step by rememberSaveable { mutableIntStateOf(0) }
  var name by rememberSaveable { mutableStateOf("") }
  var light by rememberSaveable { mutableIntStateOf(0) }
  val playback = rememberSaveable(saver = StoryPlayback.Saver) { StoryPlayback() }
  BackHandler(enabled = step > 0) { step -= 1 }
  LaunchedEffect(Unit) { graph.platform.external.prepareScanner() }
  Page {
    AnimatedContent(
      step,
      transitionSpec = {
        (fadeIn(Motion.standard()) +
          slideInHorizontally(Motion.emphasized()) { it / 8 }) togetherWith fadeOut(Motion.exit())
      },
      label = "welcome",
    ) { s ->
      when (s) {
        0 -> OnboardingStory(playback, visible = step == 0, onNext = { step = 1 })
        1 ->
          Introduce(
            name = name,
            onName = { name = it },
            light = light,
            onLight = { light = it },
            onBack = { step = 0 },
            onDone = { step = 2 },
          )
        2 -> ChoosePath(onBack = { step = 1 }, onHost = { step = 3 },
          onJoin = { onDone(Profile(name.trim(), light)) })
        else -> LinkSeeker(onBack = { step = 2 }, onDone = { onDone(Profile(name.trim(), light)) })
      }
    }
  }
}

@Composable
private fun ChoosePath(onBack: () -> Unit, onHost: () -> Unit, onJoin: () -> Unit) {
  Column(Modifier.fillMaxSize()) {
    TopBar(onBack = onBack)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter)) {
      LiveryRule()
      Spacer(Modifier.height(Space.xl))
      Text(stringResource(Res.string.story_path_title), style = Theme.type.title1)
      Spacer(Modifier.height(Space.s))
      Text(stringResource(Res.string.story_path_body), style = Theme.type.body, color = Theme.colors.contentSecondary)
      OnboardingScene({ 6.8f }, reduced = true, modifier = Modifier.fillMaxWidth().height(240.dp))
      Point(Icons.Seeker, stringResource(Res.string.story_host_help))
      Spacer(Modifier.height(Space.l))
      Point(Icons.Users, stringResource(Res.string.story_join_help))
      Spacer(Modifier.height(Space.l))
    }
    BottomActions {
      Button(stringResource(Res.string.story_have_seeker), onHost, leadingIcon = Icons.Seeker)
      Button(stringResource(Res.string.story_join_seeker), onJoin, style = ButtonStyle.Secondary, leadingIcon = Icons.Users)
    }
  }
}

@Composable
private fun Point(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = Theme.colors.contentSecondary, size = 24.dp)
    Spacer(Modifier.width(Space.l))
    Text(text, style = Theme.type.body, color = Theme.colors.content, modifier = Modifier.weight(1f))
  }
}

@Composable
private fun Introduce(
  name: String,
  onName: (String) -> Unit,
  light: Int,
  onLight: (Int) -> Unit,
  onBack: () -> Unit,
  onDone: () -> Unit,
) {
  val c = Theme.colors
  Column(Modifier.fillMaxSize()) {
    TopBar(onBack = onBack)
    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter)
    ) {
      LiveryRule()
      Spacer(Modifier.height(Space.xl))
      Text(stringResource(Res.string.label_profile), style = Theme.type.title1)
      Spacer(Modifier.height(Space.xl))
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        PlayerLight(
          name.ifBlank { "?" },
          c.light(light),
          size = Sizes.lightL + 16.dp,
        )
      }
      Spacer(Modifier.height(Space.xl))
      TextField(name, onName, label = stringResource(Res.string.label_name), maxLength = 20, autoFocus = true)
      Spacer(Modifier.height(Space.xl))
      Text(stringResource(Res.string.label_light), style = Theme.type.overline, color = c.contentSecondary)
      Spacer(Modifier.height(Space.m))
      LightPicker(light, onPick = onLight)
      Spacer(Modifier.height(Space.l))
    }
    BottomActions {
      Button(
        stringResource(Res.string.action_continue),
        onDone,
        enabled = name.isNotBlank(),
        trailingIcon = Icons.ArrowRight,
      )
    }
  }
}

@Composable
private fun LinkSeeker(onBack: () -> Unit, onDone: () -> Unit) {
  val c = Theme.colors
  val graph = LocalGraph.current
  val scope = rememberCoroutineScope()
  val status by graph.seeker.status.collectAsState()
  LaunchedEffect(status) { if (status is SeekerStatus.Verified) onDone() }
  Column(Modifier.fillMaxSize()) {
    TopBar(onBack = onBack)
    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter)
    ) {
      Text(stringResource(Res.string.story_link_title), style = Theme.type.title1)
      Spacer(Modifier.height(Space.s))
      Text(
        stringResource(Res.string.story_link_body),
        style = Theme.type.body,
        color = c.contentSecondary,
      )
      Spacer(Modifier.height(Space.x3l))
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        OnboardingScene({ 3f }, reduced = true, modifier = Modifier.fillMaxWidth().height(210.dp))
      }
      Spacer(Modifier.height(Space.x3l))
      Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Point(Icons.Seeker, stringResource(Res.string.story_verify_help))
        Point(Icons.Users, stringResource(Res.string.story_host_help))
      }
      Spacer(Modifier.height(Space.xl))
      when (val s = status) {
        is SeekerStatus.NeedsApproval ->
          Notice(s.message, tone = Tone.Warning, title = "Not linked yet")
        is SeekerStatus.NoToken ->
          Notice(
            "That wallet doesn't hold a Seeker Genesis Token. Choose the one that came with this Seeker.",
            tone = Tone.Warning,
            title = "No Seeker Genesis Token",
          )
        else -> Unit
      }
    }
    BottomActions {
      Button(
        if (status is SeekerStatus.NeedsApproval || status is SeekerStatus.NoToken) "Try again"
        else "Link my Seeker",
        { scope.launch { graph.seeker.link() } },
        style = ButtonStyle.Reward,
        loading = status == SeekerStatus.Checking,
        leadingIcon = Icons.Seeker,
      )
      Button(
        stringResource(Res.string.story_join_instead),
        onDone,
        style = ButtonStyle.Ghost,
        enabled = status != SeekerStatus.Checking,
      )
    }
  }
}

@Composable
internal fun LightPicker(selected: Int, onPick: (Int) -> Unit) {
  val c = Theme.colors
  Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
    c.lights.indices.chunked(4).forEach { row ->
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        row.forEach { i ->
          val light = c.light(i)
          Column(
            Modifier.weight(1f)
              .clip(Shapes.control)
              .selectable(selected = i == selected, onClick = { onPick(i) }, role = Role.RadioButton)
              .padding(vertical = Space.s),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Box(
              Modifier.size(56.dp)
                .then(
                  if (i == selected) Modifier.border(2.dp, c.content, Shapes.control) else Modifier
                ),
              contentAlignment = Alignment.Center,
            ) {
              PlayerLight("", light, size = 44.dp)
              if (i == selected) {
                Icon(Icons.Check, null, tint = light.content, size = 16.dp)
              }
            }
            Spacer(Modifier.height(Space.s))
            Text(
              light.name,
              style = Theme.type.caption,
              maxLines = 1,
              color = if (i == selected) c.content else c.contentSecondary,
            )
          }
        }
      }
    }
  }
}
