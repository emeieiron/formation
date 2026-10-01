package xyz.mcxross.formation.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.BackHandler
import xyz.mcxross.formation.design.components.BottomActions
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.components.ButtonStyle
import xyz.mcxross.formation.design.components.Notice
import xyz.mcxross.formation.design.components.Page
import xyz.mcxross.formation.design.components.PlayerLight
import xyz.mcxross.formation.design.components.SkrCoin
import xyz.mcxross.formation.design.components.TextField
import xyz.mcxross.formation.design.components.TopBar
import xyz.mcxross.formation.design.effects.FormationMark
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
  var light by rememberSaveable { mutableIntStateOf(6) }
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
        0 -> Intro(onNext = { step = 1 })
        1 ->
          Introduce(
            name = name,
            onName = { name = it },
            light = light,
            onLight = { light = it },
            onBack = { step = 0 },
            onDone = {
              if (graph.platform.device.seeker) step = 2 else onDone(Profile(name.trim(), light))
            },
          )
        else -> LinkSeeker(onBack = { step = 1 }, onDone = { onDone(Profile(name.trim(), light)) })
      }
    }
  }
}

@Composable
private fun Intro(onNext: () -> Unit) {
  val c = Theme.colors
  val draw = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    draw.animateTo(1f, tween(1_000, easing = Motion.standard))
  }
  Column(Modifier.fillMaxSize()) {
    Column(
      Modifier.weight(1f).padding(horizontal = Space.gutter),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Spacer(Modifier.weight(0.8f))
      FormationMark(Modifier.size(width = 160.dp, height = 96.dp), progress = draw.value)
      Spacer(Modifier.height(Space.xl))
      Text("Formation", style = Theme.type.title3, color = c.contentSecondary)
      Spacer(Modifier.height(Space.l))
      Text(
        "Rewards that only unlock together.",
        style = Theme.type.title1,
        color = c.content,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.weight(0.6f))
      Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Point(Icons.Seeker, "A Seeker receives locked SKR.")
        Point(Icons.Users, "It takes a group, together in one place.")
        Point(Icons.Unlock, "Move as one to unlock it. Everyone gets a share.")
      }
      Spacer(Modifier.weight(0.4f))
    }
    BottomActions { Button("Get started", onNext, trailingIcon = Icons.ArrowRight) }
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
      Text("What should the group call you?", style = Theme.type.title1)
      Spacer(Modifier.height(Space.s))
      Text(
        "Choose a name and a light so the group can recognise you. No account needed.",
        style = Theme.type.body,
        color = c.contentSecondary,
      )
      Spacer(Modifier.height(Space.xl))
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        PlayerLight(
          name.ifBlank { "?" },
          c.light(light),
          size = Sizes.lightL + 16.dp,
        )
      }
      Spacer(Modifier.height(Space.xl))
      TextField(name, onName, label = "Your name", maxLength = 20, autoFocus = true)
      Spacer(Modifier.height(Space.xl))
      Text("Your light", style = Theme.type.subheadStrong)
      Spacer(Modifier.height(Space.m))
      LightPicker(light, onPick = onLight)
      Spacer(Modifier.height(Space.l))
      Text(
        "You can change your name and light anytime from your profile.",
        style = Theme.type.footnote,
        color = c.contentTertiary,
      )
    }
    BottomActions {
      Button(
        "Continue",
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
      Text("Link your Seeker", style = Theme.type.title1)
      Spacer(Modifier.height(Space.s))
      Text(
        "Locked SKR for this Seeker reaches it once you link. Seed Vault will ask you to approve; nothing is spent or moved.",
        style = Theme.type.body,
        color = c.contentSecondary,
      )
      Spacer(Modifier.height(Space.x3l))
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        SkrCoin(64.dp)
      }
      Spacer(Modifier.height(Space.x3l))
      Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Point(Icons.Seeker, "Formation checks this Seeker's Genesis Token.")
        Point(Icons.Users, "Then you can host Formations for the people around you.")
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
        "Not now",
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
              Modifier.size(52.dp)
                .then(
                  if (i == selected) Modifier.border(2.dp, light.color, Shapes.circle) else Modifier
                ),
              contentAlignment = Alignment.Center,
            ) {
              PlayerLight("", light, size = 40.dp)
              if (i == selected) {
                Box(
                  Modifier.align(Alignment.BottomEnd).size(18.dp).clip(Shapes.circle).background(c.content),
                  contentAlignment = Alignment.Center,
                ) { Icon(Icons.Check, null, tint = c.background, size = 12.dp) }
              }
            }
            Spacer(Modifier.height(Space.xs))
            Text(
              light.name,
              style = Theme.type.caption,
              color = if (i == selected) c.content else c.contentSecondary,
            )
          }
        }
      }
    }
  }
}
