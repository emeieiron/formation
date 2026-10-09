package xyz.mcxross.formation.caravan

import androidx.compose.runtime.Composable
import xyz.mcxross.formation.caravan.ui.CaravanStage
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
import xyz.mcxross.formation.sensors.capabilities.InputCapability
import xyz.mcxross.formation.sensors.capabilities.SensorRequirement
import xyz.mcxross.formation.session.ChallengeGame
import xyz.mcxross.formation.session.ChallengeSetup

object Caravan : Challenge<CaravanState, Stride>() {
  override val formatVersion = 1

  override val info =
    ChallengeInfo(
      id = ChallengeId("caravan"),
      code = 10,
      title = "Caravan",
      tagline = "Walk together. Leave no one behind.",
      summary =
        "A synchronized group walk for 6 or more players. Keep pace, stay in formation, and reach your step goal together.",
      steps =
        listOf(
          Step(Icons.Users, "Form up with 6 or more players and begin walking together."),
          Step(
            Icons.Pulse,
            "Match the group cadence. If anyone pulls too far ahead, their steps pause.",
          ),
          Step(Icons.Target, "Reach the step goal together before the timer runs out."),
        ),
      icon = Icons.Users,
      light = 0,
      senses = listOf(Sense.Motion, Sense.Timing, Sense.Voice),
      players = 6..16,
      groupSizes = (6..16).toSet(),
      difficulties = emptyList(),
    )

  override val stateSerializer = CaravanState.serializer()
  override val inputSerializer = Stride.serializer()

  override fun optionalSensors(players: Int): List<SensorRequirement> =
    listOf(SensorRequirement(InputCapability.JOLT, allowEstimated = true, allowSimulated = true))

  override fun newGame(setup: ChallengeSetup): ChallengeGame<CaravanState, Stride> =
    CaravanGame(setup)

  override fun goal(players: Int, difficulty: Difficulty): String =
    "15,000 steps each · pack spread ${Pacing.MAX_SPREAD_STEPS} · ${Pacing.LIMIT_MS / 3_600_000} hours"

  override fun autopilot(state: CaravanState, me: PlayerId, now: Long): Move<Stride>? {
    if (now < state.startAt || now >= state.endsAt) return null
    val walker = state.walker(me)
    if (walker.status == WalkerStatus.WaitingForCaravan || walker.status == WalkerStatus.Finished) {
      return null
    }
    return if (now - walker.lastStepAt >= 500L) {
      val nextStep = maxOf(walker.steps, walker.lastStrideIndex) + 1
      Move("${me.value}:$nextStep", Stride(nextStep, now))
    } else {
      null
    }
  }

  @Composable override fun Stage(scope: StageScope<CaravanState, Stride>) = CaravanStage(scope)
}
