package xyz.mcxross.formation.challenge.circuit

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
import xyz.mcxross.formation.sensors.capabilities.InputCapability

object Circuit : Challenge<CircuitState, CircuitInput>() {
  override fun requiredCapabilities(players: Int): Set<String> = setOf(InputCapability.TILT.id, InputCapability.JOLT.id)

  override val info =
    ChallengeInfo(
      id = ChallengeId("circuit"),
      code = 2,
      title = "Human Circuit",
      tagline = "Pass the pulse around the group",
      summary =
        "Every phone becomes a node. A pulse runs round the circuit, and whoever it reaches must tap, hold, shake, turn, flip, match or sync to pass it on. One fumble and the circuit breaks.",
      steps =
        listOf(
          Step(Icons.Circuit, "Your phone is a node. The pulse travels round the group in order."),
          Step(
            Icons.Pulse,
            "When it reaches you, do the move on your screen before time runs out.",
          ),
          Step(Icons.Users, "Matches and syncs need your neighbours: talk to each other."),
        ),
      icon = Icons.Circuit,
      light = 4,
      senses = listOf(Sense.Touch, Sense.Motion, Sense.Voice),
    )

  override val stateSerializer = CircuitState.serializer()
  override val inputSerializer = CircuitInput.serializer()

  override fun newGame(setup: ChallengeSetup): ChallengeGame<CircuitState, CircuitInput> =
    CircuitGame(setup)

  override fun goal(players: Int, difficulty: Difficulty): String {
    val loops = CircuitGame.loops(players, difficulty)
    return "$loops ${if (loops == 1) "full loop" else "full loops"} of the circuit"
  }

  @Composable
  override fun Stage(scope: StageScope<CircuitState, CircuitInput>) = CircuitStage(scope)

  override fun autopilot(state: CircuitState, me: PlayerId, now: Long): Move<CircuitInput>? {
    val pulse = state.pulse ?: return null
    val node = state.nodes.indexOf(me)
    if (node != pulse.node && node != pulse.partner || node in pulse.synced) return null
    // A human-ish beat after the pulse lands; sync partners both land on the same beat.
    if (now < pulse.arriveAt + 350) return null
    return Move("pulse-${pulse.id}", CircuitInput(pulse.id, pulse.action, now, pulse.symbol))
  }
}
