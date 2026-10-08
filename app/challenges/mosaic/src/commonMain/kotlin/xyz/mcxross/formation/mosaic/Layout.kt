package xyz.mcxross.formation.mosaic

import kotlin.math.min
import kotlinx.serialization.Serializable
import xyz.mcxross.formation.session.ScreenProfile

// How the phones lie on the table.
@Serializable
enum class Placement {
  Landscape,
  Portrait,
}

@Serializable
enum class Edge {
  Left,
  Top,
  Right,
  Bottom;

  val opposite: Edge
    get() =
      when (this) {
        Left -> Right
        Right -> Left
        Top -> Bottom
        Bottom -> Top
      }

  // True for the edges a vertical seam line runs along.
  val vertical: Boolean
    get() = this == Left || this == Right
}

@Serializable data class Size(val width: Double, val height: Double)

@Serializable
data class Box(val left: Double, val top: Double, val width: Double, val height: Double) {
  val right: Double
    get() = left + width

  val bottom: Double
    get() = top + height
}

// A seam joins two neighbouring positions: [first] is left of or above [second].
@Serializable
data class Seam(val id: Int, val first: Int, val second: Int, val across: Boolean) {
  fun edgeOf(position: Int): Edge? =
    when (position) {
      first -> if (across) Edge.Right else Edge.Bottom
      second -> if (across) Edge.Left else Edge.Top
      else -> null
    }
}

// Positions run row by row from the top left. The three rows hold the mark's three bars.
@Serializable
data class Grid(val rows: Int, val columns: Int, val placement: Placement) {
  val size: Int
    get() = rows * columns

  fun row(position: Int) = position / columns

  fun column(position: Int) = position % columns

  fun seams(): List<Seam> {
    val across =
      (0 until rows).flatMap { row -> (0 until columns - 1).map { row * columns + it to true } }
    val down =
      (0 until rows - 1).flatMap { row -> (0 until columns).map { row * columns + it to false } }
    return (across + down).mapIndexed { id, (position, isAcross) ->
      Seam(id, position, if (isAcross) position + 1 else position + columns, isAcross)
    }
  }

  // The neighbour across [edge], if there is one.
  fun neighbour(position: Int, edge: Edge): Int? {
    val row = row(position)
    val column = column(position)
    return when (edge) {
      Edge.Left -> if (column > 0) position - 1 else null
      Edge.Right -> if (column < columns - 1) position + 1 else null
      Edge.Top -> if (row > 0) position - columns else null
      Edge.Bottom -> if (row < rows - 1) position + columns else null
    }
  }

  // An axis with neighbours on both sides can't push its fragment toward either seam.
  fun centredAcross(position: Int) = columns == 1 || column(position) in 1 until columns - 1

  fun centredDown(position: Int) = rows == 1 || row(position) in 1 until rows - 1
}

object Layouts {
  // Every layout the rules support.
  val sizes = setOf(6, 9, 18)
  // The sizes rewards can fund. Eighteen phones wait for an eighteen-phone playtest.
  val groupSizes = setOf(6, 9)

  // Bezels and cases meet at each seam; long phone edges are thinner than the ends with speakers
  // and cameras.
  const val LONG_EDGE_GAP_MM = 6.0
  const val SHORT_EDGE_GAP_MM = 10.0

  // Below this usable area one phone would shrink every fragment too far.
  const val MIN_SHORT_MM = 45.0
  const val MIN_LONG_MM = 90.0

  fun grid(players: Int): Grid =
    when (players) {
      6 -> Grid(3, 2, Placement.Landscape)
      9 -> Grid(3, 3, Placement.Landscape)
      18 -> Grid(3, 6, Placement.Portrait)
      else -> throw IllegalArgumentException("Mosaic needs 6, 9 or 18 phones")
    }

  // A phone's usable area in the layout's orientation. Landscape phones lie with their top edge to
  // the left.
  fun usable(profile: ScreenProfile, placement: Placement): Box =
    when (placement) {
      Placement.Portrait ->
        Box(profile.insets.left, profile.insets.top, profile.usableWidthMm, profile.usableHeightMm)
      Placement.Landscape ->
        Box(profile.insets.top, profile.insets.right, profile.usableHeightMm, profile.usableWidthMm)
    }

  // The largest fragment every phone can show: the narrowest usable width by the shortest usable
  // height.
  fun fragment(profiles: Collection<ScreenProfile>, placement: Placement): Size {
    require(profiles.isNotEmpty())
    val areas = profiles.map { usable(it, placement) }
    return Size(areas.minOf { it.width }, areas.minOf { it.height })
  }

  fun gaps(placement: Placement): Size =
    when (placement) {
      Placement.Landscape -> Size(SHORT_EDGE_GAP_MM, LONG_EDGE_GAP_MM)
      Placement.Portrait -> Size(LONG_EDGE_GAP_MM, SHORT_EDGE_GAP_MM)
    }
}

// The mosaic's shared canvas in millimetres. Fragments are windows onto it, with the bezels between
// phones hiding the canvas behind them.
data class Canvas(val grid: Grid, val fragment: Size, val gaps: Size) {
  val width: Double
    get() = grid.columns * fragment.width + (grid.columns - 1) * gaps.width

  val height: Double
    get() = grid.rows * fragment.height + (grid.rows - 1) * gaps.height

  // Millimetres per view-box unit, with the mark centred.
  val scale: Double
    get() = min(width / Artwork.WIDTH, height / Artwork.HEIGHT)

  val origin: Vec
    get() = Vec((width - Artwork.WIDTH * scale) / 2, (height - Artwork.HEIGHT * scale) / 2)

  fun window(position: Int): Box =
    Box(
      grid.column(position) * (fragment.width + gaps.width),
      grid.row(position) * (fragment.height + gaps.height),
      fragment.width,
      fragment.height,
    )

  fun toArtwork(point: Vec): Vec = (point - origin) * (1 / scale)

  fun toCanvas(point: Vec): Vec = origin + point * scale

  // Where the fragment sits within a phone's usable area, pushed toward the seams it can reach.
  fun placement(position: Int, usable: Box): Box {
    val spareX = usable.width - fragment.width
    val spareY = usable.height - fragment.height
    val x =
      when {
        grid.centredAcross(position) -> spareX / 2
        grid.column(position) == 0 -> spareX
        else -> 0.0
      }
    val y =
      when {
        grid.centredDown(position) -> spareY / 2
        grid.row(position) == 0 -> spareY
        else -> 0.0
      }
    return Box(usable.left + x, usable.top + y, fragment.width, fragment.height)
  }

  // The empty strip above or below the bar in a fragment, in fragment coordinates, for the HUD.
  fun quietStrip(position: Int): Box {
    val window = window(position)
    val bar =
      Artwork.bars
        .map { (origin.y + it.start * scale)..(origin.y + it.endInclusive * scale) }
        .maxBy { minOf(it.endInclusive, window.bottom) - maxOf(it.start, window.top) }
    val above = (bar.start - window.top).coerceIn(0.0, window.height)
    val below = (window.bottom - bar.endInclusive).coerceIn(0.0, window.height)
    return if (above >= below) Box(0.0, 0.0, window.width, above)
    else Box(0.0, window.height - below, window.width, below)
  }
}
