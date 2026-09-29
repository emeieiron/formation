package xyz.mcxross.formation.challenge.formation

import androidx.compose.runtime.Composable
import xyz.mcxross.formation.challenge.Challenge
import xyz.mcxross.formation.challenge.ChallengeInfo
import xyz.mcxross.formation.challenge.Move
import xyz.mcxross.formation.challenge.Role
import xyz.mcxross.formation.challenge.Sense
import xyz.mcxross.formation.challenge.StageScope
import xyz.mcxross.formation.challenge.Step
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeGame
import xyz.mcxross.formation.session.ChallengeSetup

object FormationChallenge : Challenge<FormationState, FormationInput>() {
  override val info =
    ChallengeInfo(
      id = ChallengeId("formation"),
      code = 4,
      title = "Formation",
      tagline = "Build the figure together",
      summary =
        "The Seeker lies in the middle and shows the blueprint. Everyone else holds their phone the way the blueprint says: upright, sideways, flat or face down. Hold the whole figure still to lock it in.",
      steps =
        listOf(
          Step(Icons.Seeker, "The Seeker lies face up in the middle. Only it shows the blueprint."),
          Step(Icons.Tilt, "Find your colour on the blueprint and hold your phone that way."),
          Step(Icons.Lock, "When every phone is in place, hold still until the figure locks."),
        ),
      icon = Icons.Formation,
      light = 3,
      senses = listOf(Sense.Pose, Sense.Voice),
    )

  override val stateSerializer = FormationState.serializer()
  override val inputSerializer = FormationInput.serializer()

  override fun newGame(setup: ChallengeSetup): ChallengeGame<FormationState, FormationInput> =
    FormationGame(setup)

  override fun goal(players: Int, difficulty: Difficulty) =
    "${FormationGame.figures(difficulty)} figures, held still"

  override fun role(players: List<PlayerId>, seeker: PlayerId, me: PlayerId) =
    if (me == seeker)
      Role(
        "The architect",
        "Lay your Seeker face up in the middle. Your screen shows everyone's pose: call them out.",
        Icons.Seeker,
      )
    else
      Role(
        "A builder",
        "Watch the Seeker's screen for your colour and hold your phone the way it shows.",
        Icons.Tilt,
      )

  @Composable
  override fun Stage(scope: StageScope<FormationState, FormationInput>) = FormationStage(scope)

  override fun autopilot(state: FormationState, me: PlayerId, now: Long): Move<FormationInput>? {
    val figure = state.figure ?: return null
    val target = figure.targets[me.value] ?: return null
    // A couple of seconds to "get into position", then keep saying so until the Seeker agrees.
    if (state.poses[me.value] == target || now < figure.startedAt + 2_500) return null
    return Move("figure-${figure.attempt}-${now / 600}", FormationInput(target))
  }
}
