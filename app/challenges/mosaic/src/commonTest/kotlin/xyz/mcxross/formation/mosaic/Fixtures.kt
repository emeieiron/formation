package xyz.mcxross.formation.mosaic

import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.ScreenProfile

internal const val START = 10_000L

internal fun phone(shortMm: Double = 64.0, longMm: Double = 140.0) = ScreenProfile(shortMm, longMm, pxPerMm = 16.0)

internal fun setup(
  players: Int = 6,
  difficulty: Difficulty = Difficulty.NORMAL,
  seed: Long = 7,
  screens: (Int) -> ScreenProfile = { phone() },
): ChallengeSetup {
  val roster = (1..players).map { PlayerId("p$it") }
  return ChallengeSetup(roster, roster.first(), difficulty, seed, START,
    screens = roster.mapIndexed { index, player -> player to screens(index) }.toMap())
}

// Fraction of a fragment's window covered by the mark.
internal fun coverage(canvas: Canvas, position: Int, samples: Int = 40): Double {
  val window = canvas.window(position)
  var inside = 0
  for (i in 0 until samples) for (j in 0 until samples) {
    val point = Vec(window.left + (i + 0.5) * window.width / samples, window.top + (j + 0.5) * window.height / samples)
    if (Artwork.contains(canvas.toArtwork(point))) inside++
  }
  return inside.toDouble() / (samples * samples)
}

internal fun pinch(id: Long, edge: Edge, along: Double, upAt: Long) = Pinch(id, edge, along, upAt - 150, upAt)
