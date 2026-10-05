package xyz.mcxross.formation.mosaic.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.max
import xyz.mcxross.formation.mosaic.Edge
import xyz.mcxross.formation.mosaic.Grid
import xyz.mcxross.formation.mosaic.Pinch
import xyz.mcxross.formation.session.ClockSync

// Each finger is tracked on its own, so one phone can take part in two pinches at once.
internal fun Modifier.pinchInput(piece: Piece, grid: Grid, clock: ClockSync, onPinch: (Pinch) -> Unit): Modifier =
  pointerInput(piece, grid) {
    val reader = PinchReader(piece, grid)
    awaitPointerEventScope {
      while (true) {
        awaitPointerEvent().changes.forEach { change ->
          when {
            change.changedToDownIgnoreConsumed() -> reader.down(change.id, change.position, clock.hostNow())
            change.changedToUpIgnoreConsumed() -> reader.up(change.id, change.position, clock.hostNow())?.let(onPinch)
            change.pressed -> reader.move(change.id, change.position)
          }
          change.consume()
        }
      }
    }
  }

// Turns a finger's drag into half of a pinch when it slides toward a seam and lifts near it.
internal class PinchReader(private val piece: Piece, private val grid: Grid) {
  private class Track(val start: Offset, val downAt: Long, var last: Offset)

  private val tracks = mutableMapOf<PointerId, Track>()
  private var lastId = 0L

  fun down(id: PointerId, at: Offset, now: Long) {
    tracks[id] = Track(at, now, at)
  }

  fun move(id: PointerId, at: Offset) {
    tracks[id]?.last = at
  }

  fun up(id: PointerId, at: Offset, now: Long): Pinch? {
    val track = tracks.remove(id) ?: return null
    return read(track.start, at, track.downAt, now)
  }

  fun read(start: Offset, end: Offset, downAt: Long, upAt: Long): Pinch? {
    val dx = piece.mm(end.x - start.x)
    val dy = piece.mm(end.y - start.y)
    val across = abs(dx) >= abs(dy)
    val travel = if (across) abs(dx) else abs(dy)
    val drift = if (across) abs(dy) else abs(dx)
    if (travel < MIN_TRAVEL_MM || drift > travel * MAX_DRIFT) return null
    val edge = if (across) (if (dx > 0) Edge.Right else Edge.Left) else (if (dy > 0) Edge.Bottom else Edge.Top)
    if (grid.neighbour(piece.position, edge) == null) return null
    val x = piece.mm(end.x) - piece.box.left
    val y = piece.mm(end.y) - piece.box.top
    val left = when (edge) {
      Edge.Left -> x
      Edge.Right -> piece.box.width - x
      Edge.Top -> y
      Edge.Bottom -> piece.box.height - y
    }
    if (left > REACH_MM) return null
    lastId = max(lastId + 1, upAt)
    return Pinch(lastId, edge, if (edge.vertical) y else x, downAt, upAt)
  }

  companion object {
    const val MIN_TRAVEL_MM = 6.0
    const val MAX_DRIFT = 0.85
    // The finger must lift this close to the edge, or past it into the margin or bezel.
    const val REACH_MM = 15.0
  }
}
