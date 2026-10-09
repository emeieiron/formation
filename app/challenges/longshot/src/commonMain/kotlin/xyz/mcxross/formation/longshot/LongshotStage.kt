package xyz.mcxross.formation.longshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.challenge.StageScope
import xyz.mcxross.formation.challenge.rememberHostNow
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.components.ButtonSize
import xyz.mcxross.formation.design.components.ButtonStyle
import xyz.mcxross.formation.design.components.Overline
import xyz.mcxross.formation.design.components.Spinner
import xyz.mcxross.formation.design.components.TextButton
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.tokens.Space

@Composable
fun LongshotStage(
  scope: StageScope<LongshotState, LongshotInput>,
  miningActions: @Composable () -> Unit = {},
) {
  val state = scope.state
  val now by rememberHostNow(scope.clock)
  val mine = scope.me == state.picker
  val picker = scope.player(state.picker)?.name ?: "Player"
  val remaining = ((state.deadline - now + 999) / 1000).coerceAtLeast(0)
  val c = Theme.colors
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.gutter),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(Space.l),
  ) {
    Overline("LONGSHOT · ROUND ${state.turn}")
    when (state.phase) {
      LongshotPhase.Choosing -> {
        Text(
          if (mine) "Your pick" else "$picker is picking",
          style = Theme.type.title1,
          textAlign = TextAlign.Center,
        )
        Text(
          if (mine) "Choose a number from 1 to 25." else "Which number will they back?",
          style = Theme.type.body,
          color = c.contentSecondary,
        )
        Text("${remaining}s to pick", style = Theme.type.footnote, color = c.contentSecondary)
        if (mine) {
          (1..25).chunked(5).forEach { numbers ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
              numbers.forEach { number ->
                Button(
                  "$number",
                  { scope.send(LongshotInput.Pick(state.turn, number)) },
                  modifier = Modifier.weight(1f),
                  size = ButtonSize.Medium,
                  style = ButtonStyle.Secondary,
                  enabled = remaining > 0,
                )
              }
            }
          }
        } else Spinner(28.dp)
      }
      else -> {
        Text("$picker picked", style = Theme.type.title2)
        Text(state.number?.toString() ?: "—", style = Theme.type.numeralLarge)
        when (state.phase) {
          LongshotPhase.Predicting -> {
            Text("WIN or LOSE?", style = Theme.type.title1)
            Text(
              "${remaining}s · ${state.predicted.size}/${scope.players.size - 1} locked",
              style = Theme.type.footnote,
              color = c.contentSecondary,
            )
            val prediction = state.predictions[scope.me]
            when {
              mine -> Text("The room is making its call.", style = Theme.type.body)
              prediction != null ->
                Text("You called ${prediction.name}", style = Theme.type.bodyStrong)
              else ->
                Row(
                  Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                  Prediction.entries.forEach { choice ->
                    Button(
                      choice.name,
                      { scope.send(LongshotInput.Predict(state.turn, choice)) },
                      modifier = Modifier.weight(1f),
                      enabled = remaining > 0,
                      style =
                        if (choice == Prediction.WIN) ButtonStyle.Primary
                        else ButtonStyle.Secondary,
                    )
                  }
                }
            }
          }
          LongshotPhase.Funding -> {
            Text(
              if (mine) "Confirm mining" else "Waiting for $picker to mine",
              style = Theme.type.title2,
            )
            Text(
              "${LongshotTerms.AMOUNT} on tile ${state.number}. Predictions are free.",
              style = Theme.type.body,
              textAlign = TextAlign.Center,
            )
            Predictions(scope)
            miningActions()
            if (scope.isSeeker)
              TextButton("Skip turn", { scope.send(LongshotInput.Skip(state.turn)) })
          }
          LongshotPhase.AwaitingRound,
          LongshotPhase.Verifying,
          LongshotPhase.Watching -> {
            Spinner(28.dp)
            Text(
              when {
                !state.connected -> "Reconnecting…"
                state.oreRound == null -> "Preparing mining…"
                state.phase == LongshotPhase.Verifying -> "Confirming mining…"
                else -> "Waiting for ORE's draw…"
              },
              style = Theme.type.title2,
              textAlign = TextAlign.Center,
            )
            Text(
              "Your predictions are locked.",
              style = Theme.type.body,
              color = c.contentSecondary,
              textAlign = TextAlign.Center,
            )
            Predictions(scope)
            if (scope.isSeeker)
              TextButton("Skip round", { scope.send(LongshotInput.Skip(state.turn)) })
          }
          LongshotPhase.Result -> {
            Text(
              state.outcome?.name.orEmpty(),
              style = Theme.type.title1,
              color = if (state.outcome == Prediction.WIN) c.positive else c.content,
            )
            Text("ORE picked ${state.winningNumber}", style = Theme.type.title2)
            Predictions(scope)
            miningActions()
            NextRound(scope)
          }
          LongshotPhase.Skipped -> {
            Text("Round skipped", style = Theme.type.title1)
            Text(state.message.orEmpty(), style = Theme.type.body, textAlign = TextAlign.Center)
            miningActions()
            NextRound(scope)
          }
          LongshotPhase.Choosing -> Unit
        }
      }
    }
    Spacer(Modifier.height(Space.s))
  }
}

@Composable
private fun Predictions(scope: StageScope<LongshotState, LongshotInput>) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
    scope.players
      .filter { it.id != scope.state.picker }
      .forEach { player ->
        val prediction = scope.state.predictions[player.id]
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text(player.name, modifier = Modifier.weight(1f), style = Theme.type.body)
          Text(
            prediction?.name ?: "No prediction",
            style = Theme.type.bodyStrong,
            color =
              if (prediction != null && prediction == scope.state.outcome) Theme.colors.positive
              else Theme.colors.contentSecondary,
          )
        }
      }
  }
}

@Composable
private fun NextRound(scope: StageScope<LongshotState, LongshotInput>) {
  if (scope.isSeeker) Button("Next round", { scope.send(LongshotInput.Next(scope.state.turn)) })
  else
    Text(
      "Waiting for the host to start the next round.",
      style = Theme.type.footnote,
      color = Theme.colors.contentSecondary,
    )
}
