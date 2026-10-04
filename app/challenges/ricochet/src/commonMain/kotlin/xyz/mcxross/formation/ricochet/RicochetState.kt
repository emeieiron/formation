package xyz.mcxross.formation.ricochet

import kotlinx.serialization.Serializable
import xyz.mcxross.formation.model.PlayerId

@Serializable
data class Pulse(val x: Double, val y: Double, val vx: Double, val vy: Double)

@Serializable
data class Paddle(val player: PlayerId, val side: Int, val y: Double, val sequence: Long = 0)

@Serializable
data class Target(val id: Int, val x: Double, val y: Double)

@Serializable
enum class ImpactKind { Wall, Paddle, Target, Miss }

@Serializable
data class Impact(
  val id: Long,
  val kind: ImpactKind,
  val at: Long,
  val x: Double,
  val y: Double,
  val side: Int? = null,
  val target: Int? = null,
  val grazed: Boolean = false,
)

@Serializable
data class RicochetState(
  val paddles: List<Paddle>,
  val pulse: Pulse,
  val targets: List<Target>,
  val paddleHeight: Double,
  val at: Long,
  val startAt: Long,
  val endsAt: Long,
  val serveAt: Long,
  val rally: Int = 1,
  val misses: Int = 0,
  val returns: Int = 0,
  val momentum: Momentum = Momentum(),
  val impacts: List<Impact> = emptyList(),
  val finishedAt: Long? = null,
) {
  val clears: Int get() = TARGETS - targets.size
  fun paddle(player: PlayerId): Paddle = paddles.first { it.player == player }

  companion object {
    const val TARGETS = 6
    const val MAX_MISSES = 3
    const val LIMIT_MS = 60_000L
  }
}

@Serializable
data class MovePaddle(val rally: Int, val sequence: Long, val y: Double)
