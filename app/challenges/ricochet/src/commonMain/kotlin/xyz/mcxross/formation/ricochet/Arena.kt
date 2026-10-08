package xyz.mcxross.formation.ricochet

import kotlin.random.Random
import xyz.mcxross.formation.model.Difficulty

internal object Arena {
  const val WIDTH = 2.0
  const val HEIGHT = 1.7
  const val RADIUS = 0.022
  const val PADDLE_X = 0.045
  const val PADDLE_WIDTH = 0.024
  const val TARGET_WIDTH = 0.19
  const val TARGET_HEIGHT = 0.13
  const val MAX_ANGLE = 0.9599310885968813 // 55 degrees keeps every return moving across the arena.

  fun paddleX(side: Int) = if (side == 0) PADDLE_X else WIDTH - PADDLE_X

  fun paddleY(y: Double, height: Double) = y.coerceIn(height / 2, HEIGHT - height / 2)

  fun targets(random: Random): List<Target> =
    (0..1).flatMap { side ->
      val rows = listOf(0.30, 0.85, 1.40).shuffled(random)
      listOf(0.36, 0.62, 0.76).mapIndexed { index, x ->
        Target(side * 3 + index, side + x, rows[index] + random.nextDouble(-0.04, 0.04))
      }
    }
}

internal class Pacing(difficulty: Difficulty) {
  val speed =
    when (difficulty) {
      Difficulty.EASY -> 0.72
      Difficulty.NORMAL -> 0.96
      Difficulty.HARD -> 1.18
      Difficulty.EXTREME -> 1.40
    }
  val paddleHeight =
    when (difficulty) {
      Difficulty.EASY -> 0.34
      Difficulty.NORMAL -> 0.28
      Difficulty.HARD -> 0.23
      Difficulty.EXTREME -> 0.19
    }
  val limitMs =
    when (difficulty) {
      Difficulty.EASY -> 60_000L
      Difficulty.NORMAL -> 50_000L
      Difficulty.HARD -> 45_000L
      Difficulty.EXTREME -> 40_000L
    }
}
