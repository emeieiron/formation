package xyz.mcxross.formation.mosaic

import kotlinx.serialization.Serializable
import xyz.mcxross.formation.model.PlayerId

@Serializable
enum class SeamOutcome {
  Sealed,
  Wrong,
  Misaligned,
}

@Serializable
data class SeamEvent(
  val id: Long,
  val outcome: SeamOutcome,
  val at: Long,
  val players: List<PlayerId>,
  val seam: Int? = null,
)

@Serializable
data class MosaicState(
  val grid: Grid,
  val fragment: Size,
  val gaps: Size,
  // The player at each position, row by row.
  val positions: List<PlayerId>,
  val toleranceMm: Double,
  val labels: Boolean,
  val unsealedMarks: Boolean,
  val startAt: Long,
  val endsAt: Long,
  val sealed: List<Int> = emptyList(),
  val misses: Int = 0,
  val events: List<SeamEvent> = emptyList(),
  val finishedAt: Long? = null,
) {
  val canvas: Canvas
    get() = Canvas(grid, fragment, gaps)

  val seams: List<Seam>
    get() = grid.seams()

  val won: Boolean
    get() = sealed.size == seams.size

  fun position(player: PlayerId): Int = positions.indexOf(player)

  companion object {
    const val MAX_MISSES = 3
  }
}

// One finger of a pinch: it slid toward [edge] of the phone's fragment and lifted [alongMm] from
// the
// fragment's left or top corner along that edge.
@Serializable
data class Pinch(
  val id: Long,
  val edge: Edge,
  val alongMm: Double,
  val downAt: Long,
  val upAt: Long,
)
