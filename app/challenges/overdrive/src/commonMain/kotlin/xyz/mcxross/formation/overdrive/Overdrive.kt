package xyz.mcxross.formation.overdrive

import androidx.compose.runtime.Composable
import xyz.mcxross.formation.challenge.Challenge
import xyz.mcxross.formation.challenge.ChallengeInfo
import xyz.mcxross.formation.challenge.Sense
import xyz.mcxross.formation.challenge.StageScope
import xyz.mcxross.formation.challenge.Step
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.session.ChallengeGame
import xyz.mcxross.formation.session.ChallengeSetup

object Overdrive : Challenge<OverdriveState, Rotate>() {
  override val info =
    ChallengeInfo(
      id = ChallengeId("overdrive"),
      code = 6,
      title = "Overdrive",
      tagline = "Call. Rotate. Catch.",
      summary = "Your phone shows your partner's symbol. Call it out while they guide your catch.",
      steps =
        listOf(
          Step(Icons.Target, "Call the symbol beside your partner's name."),
          Step(Icons.Refresh, "Tap to rotate your square. Catch with the symbol they call."),
          Step(
            Icons.Bolt,
            "Clear 12 waves before the clock runs out. One missed wave ends the attempt.",
          ),
        ),
      icon = Icons.Refresh,
      light = 0,
      senses = listOf(Sense.Touch, Sense.Timing, Sense.Voice),
      players = 2..2,
      difficulties = emptyList(),
    )
  override val formatVersion = 4
  override val stateSerializer = OverdriveState.serializer()
  override val inputSerializer = Rotate.serializer()
  override val cover: @Composable () -> Unit = { OverdriveCover() }

  override fun newGame(setup: ChallengeSetup): ChallengeGame<OverdriveState, Rotate> =
    OverdriveGame(setup)

  override fun goal(players: Int, difficulty: Difficulty) =
    "12 shared waves · no misses · ${OverdriveGame.TIME_LIMIT_MS / 1_000} seconds"

  @Composable override fun Stage(scope: StageScope<OverdriveState, Rotate>) = OverdriveStage(scope)
}
