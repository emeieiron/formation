package xyz.mcxross.formation.overdrive

import xyz.mcxross.formation.model.Difficulty

internal class Pacing(private val difficulty: Difficulty) {
  // Between the longest one-miss and two-miss wins: a clean run keeps about five seconds spare,
  // one failed wave leaves one or two, and two early failures run out the clock.
  val limit: Long = when (difficulty) {
    Difficulty.EASY -> 46_000L
    Difficulty.NORMAL -> 41_000L
    Difficulty.HARD -> 36_000L
    Difficulty.EXTREME -> 31_000L
  }

  // Equal steps down to half the opening fall, so each catch removes a larger share of what is left.
  fun flight(matches: Int): Long {
    val initial = when (difficulty) {
      Difficulty.EASY -> 3_500L
      Difficulty.NORMAL -> 3_000L
      Difficulty.HARD -> 2_500L
      Difficulty.EXTREME -> 2_100L
    }
    return initial - initial * matches.coerceIn(0, STEPS) / (2 * STEPS)
  }

  fun stagger(wave: Int): Long = if (wave > 4) 0 else when (difficulty) {
    Difficulty.EASY -> 900
    Difficulty.NORMAL -> 800
    Difficulty.HARD -> 650
    Difficulty.EXTREME -> 500
  }

  fun reset(cleared: Boolean, clears: Int): Long =
    if (cleared) 650L - 300L * clears.coerceIn(0, STEPS) / STEPS else 900L

  private companion object {
    const val STEPS = OverdriveState.REQUIRED_WAVES - 1
  }
}
