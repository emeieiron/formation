package xyz.mcxross.formation.challenge

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import xyz.mcxross.formation.design.tokens.Light
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.sensors.Haptics
import xyz.mcxross.formation.sensors.MotionSense
import xyz.mcxross.formation.sensors.SensorHub
import xyz.mcxross.formation.session.ClockSync

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
  val sensors: SensorHub get() = motion.sensors
  val haptics: Haptics
  val audio: StageAudio get() = StageAudio.None

  fun send(input: I)

  val isSeeker: Boolean
    get() = players.firstOrNull { it.id == me }?.seeker == true

  fun player(id: PlayerId?): PlayerView? = players.firstOrNull { it.id == id }

  fun name(id: PlayerId?): String = if (id == me) "You" else player(id)?.name ?: "Someone"
}
