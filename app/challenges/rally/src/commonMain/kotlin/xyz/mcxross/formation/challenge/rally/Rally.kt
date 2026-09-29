package xyz.mcxross.formation.challenge.rally

import androidx.compose.runtime.Composable
import xyz.mcxross.formation.challenge.Challenge
import xyz.mcxross.formation.challenge.ChallengeInfo
import xyz.mcxross.formation.challenge.Move
import xyz.mcxross.formation.challenge.Sense
import xyz.mcxross.formation.challenge.StageScope
import xyz.mcxross.formation.challenge.Step
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeGame
import xyz.mcxross.formation.session.ChallengeSetup

object Rally : Challenge<RallyState, RallyInput>() {
  override val info =
    ChallengeInfo(
      id = ChallengeId("rally"),
      code = 1,
      title = "Rally",
      tagline = "Keep the spark in the air",
      summary =
        "A spark flies from phone to phone. Return it the moment it reaches your paddle and keep the streak going until the group reaches the goal.",
      steps =
        listOf(
          Step(Icons.Rally, "The spark picks a phone at random. Watch for your name."),
          Step(Icons.Tap, "Tap, or swing your phone, as the spark meets the ring."),
          Step(Icons.Flame, "Returns build the streak. A miss resets it and costs a life."),
        ),
      icon = Icons.Rally,
      light = 1,
      senses = listOf(Sense.Timing, Sense.Touch, Sense.Motion),
    )

  override val stateSerializer = RallyState.serializer()
  override val inputSerializer = RallyInput.serializer()

  override fun newGame(setup: ChallengeSetup): ChallengeGame<RallyState, RallyInput> =
    RallyGame(setup)

  override fun goal(players: Int, difficulty: Difficulty) =
    "${RallyGame.target(players, difficulty)} returns in a row"

  @Composable override fun Stage(scope: StageScope<RallyState, RallyInput>) = RallyStage(scope)

  override fun autopilot(state: RallyState, me: PlayerId, now: Long): Move<RallyInput>? {
    val ball = state.ball ?: return null
    if (ball.to != me || now < ball.arriveAt - 30) return null
    return Move("ball-${ball.id}", RallyInput(ball.id, now))
  }
}
