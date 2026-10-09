package xyz.mcxross.formation.caravan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import xyz.mcxross.formation.caravan.CaravanState
import xyz.mcxross.formation.caravan.StepDetector
import xyz.mcxross.formation.caravan.Stride
import xyz.mcxross.formation.caravan.StrideSequence
import xyz.mcxross.formation.caravan.WalkerStatus
import xyz.mcxross.formation.challenge.Countdown
import xyz.mcxross.formation.challenge.StageScope
import xyz.mcxross.formation.challenge.rememberHostNow
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.components.ButtonStyle
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.tokens.Radius
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.sensors.api.SensorUpdate

@Composable
internal fun CaravanStage(scope: StageScope<CaravanState, Stride>) {
  val state = scope.state
  val colors = Theme.colors
  val now by rememberHostNow(scope.clock)
  val myWalker = state.walker(scope.me)
  val strides = remember(scope) { StrideSequence() }

  // Track step detection via sensors
  LaunchedEffect(scope) {
    val detector = StepDetector()
    val channel = scope.sensors.linearAcceleration
    channel.observe().collect { update ->
      if (update is SensorUpdate.Reading) {
        val hostNow = scope.clock.hostNow()
        if (
          hostNow >= scope.state.startAt &&
            hostNow < scope.state.endsAt &&
            detector.onLinearAcceleration(update.sample.value, hostNow)
        ) {
          scope.haptics.tick()
          scope.send(strides.next(scope.state.walker(scope.me), hostNow))
        }
      }
    }
  }

  // Provide haptic warning when paused by the pack rule
  var previousStatus by remember(scope) { mutableIntStateOf(myWalker.status.ordinal) }
  LaunchedEffect(myWalker.status) {
    if (
      myWalker.status == WalkerStatus.WaitingForCaravan && previousStatus != myWalker.status.ordinal
    ) {
      scope.haptics.reject()
    }
    previousStatus = myWalker.status.ordinal
  }

  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(colors.background)
        .statusBarsPadding()
        .navigationBarsPadding()
  ) {
    Column(
      modifier =
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Space.xl),
      verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
      // HUD (Progress & Timer)
      CaravanHud(state)

      // Personal Hero Step Card
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .padding(horizontal = Space.gutter)
            .clip(RoundedCornerShape(Radius.m))
            .background(colors.surface)
            .padding(Space.l),
        contentAlignment = Alignment.Center,
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
          Text("YOUR STEPS", style = Theme.type.caption, color = colors.contentSecondary)
          Text("${myWalker.steps}", style = Theme.type.numeralHero, color = colors.content)
          Text(
            "Goal: ${state.targetSteps} steps",
            style = Theme.type.caption,
            color = colors.contentSecondary,
          )

          Spacer(Modifier.height(Space.s))

          // Status Banner
          when (myWalker.status) {
            WalkerStatus.WaitingForCaravan -> {
              Box(
                modifier =
                  Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.xs))
                    .background(colors.warning.copy(alpha = 0.2f))
                    .padding(Space.s),
                contentAlignment = Alignment.Center,
              ) {
                Text(
                  "WAIT FOR SQUAD — You're too far ahead!",
                  style = Theme.type.caption,
                  color = colors.warning,
                  textAlign = TextAlign.Center,
                )
              }
            }
            WalkerStatus.Lagging -> {
              Box(
                modifier =
                  Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.xs))
                    .background(colors.warning.copy(alpha = 0.15f))
                    .padding(Space.s),
                contentAlignment = Alignment.Center,
              ) {
                Text(
                  "PICK UP PACE — Caravan is waiting!",
                  style = Theme.type.caption,
                  color = colors.warning,
                  textAlign = TextAlign.Center,
                )
              }
            }
            WalkerStatus.Leading -> {
              Text("Leading the squad pace", style = Theme.type.caption, color = colors.accent)
            }
            WalkerStatus.Finished -> {
              Text(
                "Step goal reached! Keep moving together.",
                style = Theme.type.caption,
                color = colors.positive,
              )
            }
            WalkerStatus.Pacing -> {
              Text(
                "In formation with squad",
                style = Theme.type.caption,
                color = colors.contentSecondary,
              )
            }
          }

          Spacer(Modifier.height(Space.s))

          // Tap to stride assist (useful for testing or mobility assist)
          Button(
            text = "Tap to Step",
            onClick = {
              val hostNow = scope.clock.hostNow()
              if (hostNow >= scope.state.startAt && hostNow < scope.state.endsAt) {
                scope.haptics.tick()
                scope.send(strides.next(scope.state.walker(scope.me), hostNow))
              }
            },
            style = ButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }

      // Squad Members List
      SquadOverview(
        state = state,
        me = scope.me,
        players = scope.players,
        modifier = Modifier.padding(horizontal = Space.gutter),
      )
    }

    // Starting Countdown Overlay
    if (now < state.startAt) {
      Countdown(
        goAt = state.startAt,
        clock = scope.clock,
        modifier = Modifier.fillMaxSize().background(colors.scrim),
        go = "Walk!",
      )
    }
  }
}
