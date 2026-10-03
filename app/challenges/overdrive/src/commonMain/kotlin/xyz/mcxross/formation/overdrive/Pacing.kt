package xyz.mcxross.formation.overdrive

import xyz.mcxross.formation.model.Difficulty

internal class Pacing(private val difficulty: Difficulty) {
  fun flight(wave: Int): Long {
    val phase = when { wave <= 4 -> 0; wave <= 8 -> 1; else -> 2 }
    return when (difficulty) {
      Difficulty.EASY -> listOf(3_500L, 3_000L, 2_500L)
      Difficulty.NORMAL -> listOf(3_000L, 2_500L, 2_000L)
      Difficulty.HARD -> listOf(2_500L, 2_100L, 1_700L)
      Difficulty.EXTREME -> listOf(2_100L, 1_700L, 1_400L)
    }[phase]
  }

  fun stagger(wave: Int): Long = if (wave > 4) 0 else when (difficulty) {
    Difficulty.EASY -> 900
    Difficulty.NORMAL -> 800
    Difficulty.HARD -> 650
    Difficulty.EXTREME -> 500
  }

  fun reset(cleared: Boolean): Long = if (cleared) 650 else 900
}
