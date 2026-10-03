package xyz.mcxross.formation.overdrive

import xyz.mcxross.formation.model.Difficulty

internal class Pacing(private val difficulty: Difficulty) {
  fun flight(matches: Int): Long {
    val initial = when (difficulty) {
      Difficulty.EASY -> 3_500L
      Difficulty.NORMAL -> 3_000L
      Difficulty.HARD -> 2_500L
      Difficulty.EXTREME -> 2_100L
    }
    val steps = OverdriveState.REQUIRED_WAVES - 1
    return initial * steps / (steps + matches.coerceAtLeast(0))
  }

  fun stagger(wave: Int): Long = if (wave > 4) 0 else when (difficulty) {
    Difficulty.EASY -> 900
    Difficulty.NORMAL -> 800
    Difficulty.HARD -> 650
    Difficulty.EXTREME -> 500
  }

  fun reset(cleared: Boolean): Long = if (cleared) 650 else 900
}
