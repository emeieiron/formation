package xyz.mcxross.formation.mosaic.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import xyz.mcxross.formation.mosaic.Artwork
import xyz.mcxross.formation.mosaic.Box
import xyz.mcxross.formation.mosaic.Edge
import xyz.mcxross.formation.mosaic.Layouts
import xyz.mcxross.formation.mosaic.MosaicState
import xyz.mcxross.formation.mosaic.PathData
import xyz.mcxross.formation.mosaic.Placement
import xyz.mcxross.formation.mosaic.Segment
import xyz.mcxross.formation.session.ScreenProfile

// The logomark in view-box units, with its own gradient.
internal object Mark {
  val path: Path by lazy {
    Path().apply {
      Artwork.contours.map(PathData::parse).forEach { contour ->
        moveTo(contour.start.x.toFloat(), contour.start.y.toFloat())
        contour.segments.forEach { segment ->
          when (segment) {
            is Segment.Line -> lineTo(segment.to.x.toFloat(), segment.to.y.toFloat())
            is Segment.Cubic -> cubicTo(segment.first.x.toFloat(), segment.first.y.toFloat(),
              segment.second.x.toFloat(), segment.second.y.toFloat(), segment.to.x.toFloat(), segment.to.y.toFloat())
          }
        }
        close()
      }
    }
  }

  val brush: Brush by lazy {
    Brush.linearGradient(
      *Artwork.stops.map { (offset, argb) -> offset to Color(argb) }.toTypedArray(),
      start = Offset(Artwork.gradientFrom.x.toFloat(), Artwork.gradientFrom.y.toFloat()),
      end = Offset(Artwork.gradientTo.x.toFloat(), Artwork.gradientTo.y.toFloat()),
    )
  }
}

// One phone's piece in screen pixels, in the layout's orientation.
internal class Piece(state: MosaicState, profile: ScreenProfile, val position: Int) {
  private val canvas = state.canvas
  private val pxPerMm = profile.pxPerMm.toFloat()
  val window: Box = canvas.window(position)
  val box: Box = canvas.placement(position, Layouts.usable(profile, state.grid.placement))
  val rect = Rect(px(box.left), px(box.top), px(box.right), px(box.bottom))

  // Draw the mark at [artworkOrigin] scaled by [artworkScale] to place it behind this window.
  val artworkOrigin = Offset(px(box.left + canvas.origin.x - window.left), px(box.top + canvas.origin.y - window.top))
  val artworkScale = (canvas.scale * profile.pxPerMm).toFloat()

  fun px(mm: Double) = (mm * pxPerMm).toFloat()

  fun mm(px: Float) = px / pxPerMm.toDouble()

  fun edgeLine(edge: Edge): Pair<Offset, Offset> = when (edge) {
    Edge.Left -> rect.topLeft to rect.bottomLeft
    Edge.Right -> rect.topRight to rect.bottomRight
    Edge.Top -> rect.topLeft to rect.topRight
    Edge.Bottom -> rect.bottomLeft to rect.bottomRight
  }
}

// Lays out landscape content sideways: the phone's top edge ends up on the content's left.
@Composable
internal fun Oriented(placement: Placement, modifier: Modifier, content: @Composable () -> Unit) {
  Layout(content, modifier) { measurables, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val landscape = placement == Placement.Landscape
    val inner = if (landscape) Constraints.fixed(height, width) else Constraints.fixed(width, height)
    val placeables = measurables.map { it.measure(inner) }
    layout(width, height) {
      placeables.forEach {
        if (landscape) it.placeWithLayer((width - height) / 2, (height - width) / 2) { rotationZ = 90f }
        else it.place(0, 0)
      }
    }
  }
}

internal fun pieceLabel(state: MosaicState, position: Int): String {
  val grid = state.grid
  val bar = when (grid.row(position)) {
    0 -> "Top bar"
    grid.rows - 1 -> "Bottom bar"
    else -> "Middle bar"
  }
  val column = grid.column(position)
  val part = when {
    grid.columns == 2 -> if (column == 0) "left half" else "right half"
    column == 0 -> "left end"
    column == grid.columns - 1 -> "right end"
    grid.columns == 3 -> "centre"
    else -> "piece ${column + 1} of ${grid.columns}"
  }
  return "$bar · $part"
}
