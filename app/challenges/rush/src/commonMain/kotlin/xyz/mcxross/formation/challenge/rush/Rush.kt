package xyz.mcxross.formation.challenge.rush

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

object Rush : Challenge<RushState, RushInput>() {
  override fun requiredCapabilities(players: Int): Set<String> = if (players >= 4) setOf("ACCELEROMETER") else emptySet()

  override val info =
    ChallengeInfo(
      id = ChallengeId("rush"),
      code = 5,
      title = "Rush",
      tagline = "Keep the core alive together",
      summary =
        "The group runs one reactor. Each phone controls a different system: coolant, charge, balance, pressure or spin. Keep every gauge in its band as the drift speeds up, and survive until the clock runs out.",
      steps =
        listOf(
          Step(
            Icons.Rush,
            "Your phone runs one system of the reactor. Its gauge is yours to watch.",
          ),
          Step(Icons.Bolt, "Keep your reading inside the band: hold, tap, tilt, shake or spin."),
          Step(Icons.Flame, "Every level the drift gets faster. Call out when you need a hand."),
        ),
      icon = Icons.Rush,
      light = 0,
      senses = listOf(Sense.Touch, Sense.Motion, Sense.Voice),
    )

  override val stateSerializer = RushState.serializer()
  override val inputSerializer = RushInput.serializer()

  override fun newGame(setup: ChallengeSetup): ChallengeGame<RushState, RushInput> = RushGame(setup)

  override fun goal(players: Int, difficulty: Difficulty) =
    "Keep the core stable for ${RushGame.duration(difficulty) / 1000} s"

  override fun role(players: List<PlayerId>, seeker: PlayerId, me: PlayerId): Role? {
    val systems = RushGame.systems(players.size)
    val index = players.indexOf(me).takeIf { it >= 0 } ?: return null
    val system = systems[index % systems.size]
    return Role(system.title, "${system.verb}. ${system.hint}", system.icon)
  }

  @Composable override fun Stage(scope: StageScope<RushState, RushInput>) = RushStage(scope)

  override fun autopilot(state: RushState, me: PlayerId, now: Long): Move<RushInput>? {
    val gauge = state.gauges.firstOrNull { me in it.crew } ?: return null
    val mid = (gauge.lo + gauge.hi) / 2
    val beat = now / 150
    return when (gauge.system) {
      ReactorSystem.COOLANT -> {
        val vent = gauge.value > mid
        Move("hold-$vent-${now / 1_000}", RushInput.Hold(vent))
      }
      ReactorSystem.CHARGE -> if (gauge.value < mid) Move("tap-$beat", RushInput.Tap) else null
      ReactorSystem.BALANCE ->
        Move("tilt-$beat", RushInput.Tilt(((mid - gauge.value) * 8f).coerceIn(-1f, 1f)))
      ReactorSystem.PRESSURE ->
        if (gauge.value > mid) Move("shake-${now / 400}", RushInput.Shake) else null
      ReactorSystem.SPIN -> Move("spin-$beat", RushInput.Spin(if (gauge.value < mid) 3f else 0f))
    }
  }
}
