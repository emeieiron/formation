package xyz.mcxross.formation.challenge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.vector.ImageVector
import xyz.mcxross.formation.design.tokens.Light
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.sensors.Haptics
import xyz.mcxross.formation.sensors.MotionSense
import xyz.mcxross.formation.session.ChallengeRules
import xyz.mcxross.formation.session.ClockSync

abstract class Challenge<S : Any, I : Any> : ChallengeRules<S, I> {
  abstract val info: ChallengeInfo

  abstract fun goal(players: Int, difficulty: Difficulty): String

  open fun role(players: List<PlayerId>, seeker: PlayerId, me: PlayerId): Role? = null

  @Composable abstract fun Stage(scope: StageScope<S, I>)

  // Debug builds only: called every few frames; each distinct [Move.key] is sent once.
  open fun autopilot(state: S, me: PlayerId, now: Long): Move<I>? = null

  final override val id: ChallengeId
    get() = info.id
}

data class Move<out I>(val key: String, val input: I)

@Immutable
data class ChallengeInfo(
  val id: ChallengeId,
  // Stored on chain by the vault program; never renumber.
  val code: Int,
  val title: String,
  val tagline: String,
  val summary: String,
  val steps: List<Step>,
  val icon: ImageVector,
  val light: Int,
  val senses: List<Sense>,
  val players: IntRange = 2..32,
)

@Immutable data class Step(val icon: ImageVector, val text: String)

@Immutable data class Role(val title: String, val text: String, val icon: ImageVector)

enum class Sense(val label: String) {
  Touch("Touch"),
  Timing("Timing"),
  Motion("Motion"),
  Pose("Pose"),
  Voice("Talk it out"),
}

@Immutable
data class PlayerView(
  val id: PlayerId,
  val name: String,
  val light: Light,
  val seeker: Boolean,
  val connected: Boolean,
)

@Stable
interface StageScope<S : Any, I : Any> {
  val state: S
  val me: PlayerId
  val players: List<PlayerView>
  val round: Int
  val clock: ClockSync
  val motion: MotionSense
  val haptics: Haptics

  fun send(input: I)

  val isSeeker: Boolean
    get() = players.firstOrNull { it.id == me }?.seeker == true

  fun player(id: PlayerId?): PlayerView? = players.firstOrNull { it.id == id }

  fun name(id: PlayerId?): String = if (id == me) "You" else player(id)?.name ?: "Someone"
}
