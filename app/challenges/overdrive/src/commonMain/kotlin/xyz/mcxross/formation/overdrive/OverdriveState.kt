package xyz.mcxross.formation.overdrive

import kotlinx.serialization.Serializable
import xyz.mcxross.formation.model.PlayerId

@Serializable
enum class Symbol(val label: String) {
  Triangle("Triangle"),
  Circle("Circle"),
  Cross("Cross"),
  Diamond("Diamond"),
}

@Serializable
enum class Catch { Pending, Caught, Missed }

@Serializable
data class Dial(
  val player: PlayerId,
  val edges: List<Symbol>,
  val turns: Int = 0,
  val clue: Symbol? = null,
  val nextClue: Symbol? = null,
  val launchAt: Long,
  val catchAt: Long,
  val result: Catch = Catch.Pending,
) {
  fun facing(turns: Int = this.turns): Symbol = edges[(-turns).mod(edges.size)]

  fun progress(now: Long): Float {
    return ((now - launchAt).toDouble() / (catchAt - launchAt)).coerceIn(0.0, 1.0).toFloat()
  }
}

@Serializable
data class WaveResult(val wave: Int, val at: Long, val cleared: Boolean)

@Serializable
data class OverdriveState(
  val dials: List<Dial>,
  val wave: Int,
  val waveAt: Long,
  val nextWaveAt: Long,
  val startAt: Long,
  val endsAt: Long,
  val clears: Int = 0,
  val misses: Int = 0,
  val preview: Boolean = false,
  val lastWave: WaveResult? = null,
) {
  fun dial(player: PlayerId): Dial = dials.first { it.player == player }
  fun partner(player: PlayerId): Dial = dials.first { it.player != player }

  companion object {
    const val REQUIRED_WAVES = 12
    const val MAX_MISSES = 3
  }
}

@Serializable
data class Rotate(val wave: Int, val turn: Int, val at: Long = Long.MAX_VALUE)
