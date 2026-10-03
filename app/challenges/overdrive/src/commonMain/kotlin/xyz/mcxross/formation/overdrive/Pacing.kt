package xyz.mcxross.formation.overdrive

import xyz.mcxross.formation.model.Difficulty

internal class Pacing(private val difficulty: Difficulty) {
  // Between the longest one-miss and two-miss wins: a clean run keeps about five seconds spare,
  // one failed wave leaves one or two, and two early failures run out the clock.
  val limit: Long = when (difficulty) {
    Difficulty.EASY -> 36_000L
    Difficulty.NORMAL -> 32_000L
    Difficulty.HARD -> 28_000L
    Difficulty.EXTREME -> 25_000L
  }

  // Every cleared wave cuts the fall by the same share for both players: about 2.5 times faster by the last wave.
  fun flight(clears: Int): Long {
    var fall = when (difficulty) {
      Difficulty.EASY -> 2_800L
      Difficulty.NORMAL -> 2_400L
      Difficulty.HARD -> 2_000L
      Difficulty.EXTREME -> 1_700L
    }
    repeat(clears.coerceIn(0, STEPS)) { fall = fall * (100 - SPEEDUP_PERCENT) / 100 }
    return fall
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
    const val SPEEDUP_PERCENT = 8
  }
}
