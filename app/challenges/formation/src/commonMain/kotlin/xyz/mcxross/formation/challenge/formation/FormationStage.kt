package xyz.mcxross.formation.challenge.formation

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.challenge.FlashLayer
import xyz.mcxross.formation.challenge.Hud
import xyz.mcxross.formation.challenge.StageScope
import xyz.mcxross.formation.challenge.rememberFlash
import xyz.mcxross.formation.challenge.rememberHostNow
import xyz.mcxross.formation.challenge.rememberPose
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.PlayerLight
import xyz.mcxross.formation.design.components.ProgressRing
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.sensors.Pose

@Composable
internal fun FormationStage(scope: StageScope<FormationState, FormationInput>) {
  val state = scope.state
  val c = Theme.colors
  val pose by rememberPose(scope.motion)
  val flash = rememberFlash()

  // Report every change of pose; the Seeker decides whether the figure holds.
  LaunchedEffect(pose, state.figure?.attempt) { pose?.let { scope.send(FormationInput(it)) } }
  LaunchedEffect(state.event) {
    when (state.event) {
      is FigureEvent.Locked -> {
        scope.haptics.heavy()
        flash.fire(c.positive)
      }
      is FigureEvent.TimedOut -> {
        scope.haptics.reject()
        flash.fire(c.negative)
      }
      null -> {}
    }
  }
  LaunchedEffect(state.holdFrom != null) { if (state.holdFrom != null) scope.haptics.tick() }

  Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
      Hud(
        label = "Figure ${(state.done + 1).coerceAtMost(state.figures)} of ${state.figures}",
        progress = state.done.toFloat() / state.figures,
        lives = state.lives,
        maxLives = state.maxLives,
        accent = c.light(3).color,
      )
      Box(Modifier.weight(1f).fillMaxWidth()) {
        if (scope.me == state.architect) Blueprint(scope, state) else Builder(scope, state, pose)
      }
    }
    FlashLayer(flash)
  }
}

@Composable
private fun Blueprint(scope: StageScope<FormationState, FormationInput>, state: FormationState) {
  val c = Theme.colors
  val figure = state.figure
  val now by rememberHostNow(scope.clock)
  Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
    if (figure == null) {
      Between(scope, state, Modifier.fillMaxSize())
      return@Column
    }
    val builders = scope.players.filter { it.id != state.architect }
    val placed = builders.count { state.inPlace(it.id) }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("The blueprint", style = Theme.type.title2)
        Text(
          "Only you can see this. Call out the poses!",
          style = Theme.type.footnote,
          color = c.contentSecondary,
        )
      }
      HoldMeter(state, now)
    }
    Spacer(Modifier.height(Space.m))
    // The figure only locks with the Seeker lying face up in the middle, showing this screen.
    val flat = state.inPlace(state.architect)
    Row(verticalAlignment = Alignment.CenterVertically) {
      PoseGlyph(Pose.FACE_UP, if (flat) c.positive else c.warning, Modifier.size(28.dp))
      Spacer(Modifier.width(Space.s))
      Text(
        if (flat) "Your Seeker is lying face up" else "Lay your Seeker flat, screen up",
        style = Theme.type.subheadStrong,
        color = if (flat) c.positive else c.warning,
        modifier = Modifier.weight(1f),
      )
      Text(
        "$placed of ${builders.size} in place",
        style = Theme.type.subheadStrong,
        color = if (placed == builders.size) c.positive else c.contentSecondary,
      )
    }
    Spacer(Modifier.height(Space.m))
    LazyVerticalGrid(
      GridCells.Fixed(2),
      Modifier.weight(1f),
      horizontalArrangement = Arrangement.spacedBy(Space.m),
      verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
      items(builders, key = { it.id.value }) { player ->
        val target = figure.targets[player.id.value] ?: Pose.TILTED
        val ok = state.inPlace(player.id)
        Column(
          Modifier.clip(Shapes.card)
            .background(c.surface)
            .border(if (ok) 2.dp else 1.dp, if (ok) c.positive else c.line, Shapes.card)
            .padding(Space.m),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            PlayerLight(player.name, player.light, size = 24.dp)
            Spacer(Modifier.width(6.dp))
            Text(
              player.name,
              style = Theme.type.subheadStrong,
              maxLines = 1,
              modifier = Modifier.weight(1f),
            )
            if (ok) Icon(Icons.Check, "In place", tint = c.positive, size = 18.dp)
          }
          Spacer(Modifier.height(Space.s))
          PoseGlyph(target, player.light.color, Modifier.size(84.dp))
          Text(
            target.instruction,
            style = Theme.type.caption,
            color = c.contentSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
          )
        }
      }
    }
    Deadline(figure, now)
  }
}

@Composable
private fun Builder(
  scope: StageScope<FormationState, FormationInput>,
  state: FormationState,
  pose: Pose?,
) {
  val c = Theme.colors
  val me = scope.player(scope.me)
  val color = me?.light?.color ?: c.accent
  val architect = scope.player(state.architect)
  val figure = state.figure
  val now by rememberHostNow(scope.clock)
  val glow by
    rememberInfiniteTransition(label = "hold")
      .animateFloat(0.3f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "glow")
  Box(Modifier.fillMaxSize()) {
    Canvas(Modifier.fillMaxSize()) {
      drawRect(
        Brush.verticalGradient(
          listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.12f), Color.Transparent)
        )
      )
    }
    Column(
      Modifier.fillMaxSize().padding(Space.gutter),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      if (figure == null) {
        Between(scope, state, Modifier.weight(1f))
        return@Column
      }
      Spacer(Modifier.height(Space.xl))
      Text(me?.light?.name?.uppercase() ?: "", style = Theme.type.overline, color = c.content)
      Text(me?.name ?: "", style = Theme.type.hero, color = c.content)
      Spacer(Modifier.weight(1f))
      if (state.holdFrom != null) {
        Text("HOLD STILL", style = Theme.type.display, color = Color.White.copy(alpha = glow))
      } else {
        Icon(Icons.Seeker, null, tint = c.content, size = 40.dp)
        Spacer(Modifier.height(Space.m))
        Text(
          "Look at ${architect?.name ?: "the Seeker"}'s screen for your pose",
          style = Theme.type.title3,
          color = c.content,
          textAlign = TextAlign.Center,
        )
      }
      Spacer(Modifier.weight(1f))
      Row(
        Modifier.clip(Shapes.pill)
          .background(c.background.copy(alpha = 0.55f))
          .padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        pose?.let { PoseGlyph(it, c.content, Modifier.size(28.dp)) }
        Spacer(Modifier.width(Space.s))
        Text(
          pose?.let { "Your phone: ${it.label.lowercase()}" } ?: "Waiting for motion",
          style = Theme.type.subheadStrong,
          color = c.content,
        )
      }
      Spacer(Modifier.height(Space.l))
      Deadline(figure, now)
    }
  }
}

@Composable
private fun HoldMeter(state: FormationState, now: Long) {
  val c = Theme.colors
  val held = state.holdFrom?.let { ((now - it).toFloat() / state.holdMs).coerceIn(0f, 1f) } ?: 0f
  ProgressRing(held, Modifier.size(56.dp), stroke = 5.dp, color = c.positive, animate = false) {
    Icon(
      if (held >= 1f) Icons.Lock else Icons.Unlock,
      null,
      tint = if (state.holdFrom != null) c.positive else c.contentTertiary,
      size = 22.dp,
    )
  }
}

@Composable
private fun Deadline(figure: Figure, now: Long) {
  val c = Theme.colors
  val left = ((figure.deadline - now) / 1000).coerceAtLeast(0)
  val fraction =
    ((figure.deadline - now).toFloat() / (figure.deadline - figure.startedAt)).coerceIn(0f, 1f)
  Column(
    Modifier.fillMaxWidth().padding(vertical = Space.m),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(Modifier.fillMaxWidth().height(4.dp).clip(Shapes.pill).background(c.surfaceHigher)) {
      Box(
        Modifier.fillMaxWidth(fraction)
          .height(4.dp)
          .clip(Shapes.pill)
          .background(if (left <= 5) c.negative else c.content)
      )
    }
    Spacer(Modifier.height(Space.xs))
    Text(
      "${left}s",
      style = Theme.type.caption,
      color = if (left <= 5) c.negative else c.contentSecondary,
    )
  }
}

@Composable
private fun Between(
  scope: StageScope<FormationState, FormationInput>,
  state: FormationState,
  modifier: Modifier,
) {
  val c = Theme.colors
  Column(
    modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    when (val e = state.event) {
      is FigureEvent.Locked -> {
        Icon(Icons.Lock, null, tint = c.positive, size = 48.dp)
        Spacer(Modifier.height(Space.m))
        Text("Locked in", style = Theme.type.display, color = c.positive)
        Spacer(Modifier.height(Space.s))
        Text(
          "Built in ${e.tookMs / 1000}s. Next figure coming…",
          style = Theme.type.body,
          color = c.contentSecondary,
        )
      }
      is FigureEvent.TimedOut -> {
        Text("Out of time", style = Theme.type.display, color = c.negative)
        Spacer(Modifier.height(Space.s))
        Text(
          "Not in place: " + e.missing.joinToString { scope.name(it) },
          style = Theme.type.body,
          color = c.contentSecondary,
          textAlign = TextAlign.Center,
        )
      }
      null -> Text("Get into position…", style = Theme.type.title2, color = c.content)
    }
  }
}
