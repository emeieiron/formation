package xyz.mcxross.formation.overdrive

import kotlin.math.ceil
import kotlin.math.sqrt
import xyz.mcxross.formation.model.Difficulty

internal class Pacing(private val difficulty: Difficulty) {
  data class Flight(val duration: Long, val acceleration: Float)

  fun flight(elapsedMs: Long): Flight {
    val initial = when (difficulty) {
      Difficulty.EASY -> 3_500.0
      Difficulty.NORMAL -> 3_000.0
      Difficulty.HARD -> 2_500.0
      Difficulty.EXTREME -> 2_100.0
    }
    val increase = 1.0 / OverdriveState.LIMIT_MS
    val speed = 1.0 + elapsedMs.coerceAtLeast(0) * increase
    // Integrate the rising speed and solve for arrival; normalize the same curve for rendering.
    val duration = ceil(2 * initial / (speed + sqrt(speed * speed + 2 * increase * initial))).toLong()
    val accelerated = increase * duration * duration * 0.5
    return Flight(duration, (accelerated / (speed * duration + accelerated)).toFloat())
  }

  fun stagger(wave: Int): Long = if (wave > 4) 0 else when (difficulty) {
    Difficulty.EASY -> 900
    Difficulty.NORMAL -> 800
    Difficulty.HARD -> 650
    Difficulty.EXTREME -> 500
  }

  fun reset(cleared: Boolean): Long = if (cleared) 650 else 900
}
