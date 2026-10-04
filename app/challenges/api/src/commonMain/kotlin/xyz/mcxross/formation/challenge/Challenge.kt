package xyz.mcxross.formation.challenge

import androidx.compose.runtime.Composable
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.sensors.capabilities.InputCapability
import xyz.mcxross.formation.sensors.capabilities.SensorRequirement
import xyz.mcxross.formation.session.ChallengeRules

abstract class Challenge<S : Any, I : Any> : ChallengeRules<S, I> {
  abstract val info: ChallengeInfo

  open fun requiredSensors(players: Int): List<SensorRequirement> = emptyList()
  open fun optionalSensors(players: Int): List<SensorRequirement> = emptyList()
  open fun activeSensors(state: S, player: PlayerId, players: Int): Set<InputCapability> =
    requiredSensors(players).map { it.capability }.toSet()

  final override fun requiredCapabilities(players: Int) = requiredSensors(players).map { it.capability.id }.toSet()
  final override fun optionalCapabilities(players: Int) = optionalSensors(players).map { it.capability.id }.toSet()
  final override fun activeCapabilities(state: S, player: PlayerId, players: Int) =
    activeSensors(state, player, players).map { it.id }.toSet()

  abstract fun goal(players: Int, difficulty: Difficulty): String

  open fun role(players: List<PlayerId>, seeker: PlayerId, me: PlayerId): Role? = null

  open val introduction: (@Composable () -> Unit)? = null

  @Composable abstract fun Stage(scope: StageScope<S, I>)

  // Debug builds only: called every few frames; each distinct [Move.key] is sent once.
  open fun autopilot(state: S, me: PlayerId, now: Long): Move<I>? = null

  final override val id: ChallengeId
    get() = info.id
}

data class Move<out I>(val key: String, val input: I)
