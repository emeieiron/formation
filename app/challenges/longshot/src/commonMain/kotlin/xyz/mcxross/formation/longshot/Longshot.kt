package xyz.mcxross.formation.longshot

import androidx.compose.runtime.Composable
import xyz.mcxross.formation.challenge.Challenge
import xyz.mcxross.formation.challenge.ChallengeInfo
import xyz.mcxross.formation.challenge.Sense
import xyz.mcxross.formation.challenge.StageScope
import xyz.mcxross.formation.challenge.Step
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.session.ChallengeSetup

object Longshot : Challenge<LongshotState, LongshotInput>() {
  override val formatVersion = 2

  override val reconnectGraceMs = 180_000L

  override val resumesAfterReconnect = true

  override val info =
    ChallengeInfo(
      id = ChallengeId("longshot"),
      code = 9,
      title = "Longshot",
      tagline = "Pick a number. Call the outcome.",
      summary = "One player mines with ORE. Everyone else calls WIN or LOSE.",
      steps =
        listOf(
          Step(
            Icons.User,
            "A randomly selected player picks 1–25 and mines with 0.001 test SOL.",
          ),
          Step(Icons.Users, "Everyone else locks a WIN or LOSE prediction."),
          Step(
            Icons.Target,
            "The selected player approves mining in their wallet. Watch ORE reveal its winning tile.",
          ),
        ),
      icon = Icons.Target,
      light = 3,
      senses = listOf(Sense.Touch, Sense.Voice),
      players = 2..8,
      rewards = false,
      difficulties = emptyList(),
    )
  override val stateSerializer = LongshotState.serializer()
  override val inputSerializer = LongshotInput.serializer()

  override fun newGame(setup: ChallengeSetup) = LongshotGame(setup)

  override fun goal(players: Int, difficulty: Difficulty) =
    "Call WIN if the picked number will match ORE's winning tile, or LOSE if it won't."

  @Composable
  override fun Stage(scope: StageScope<LongshotState, LongshotInput>) = LongshotStage(scope)
}
