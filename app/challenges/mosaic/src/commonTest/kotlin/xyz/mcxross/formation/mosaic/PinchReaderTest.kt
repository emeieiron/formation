package xyz.mcxross.formation.mosaic

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mcxross.formation.mosaic.ui.Piece
import xyz.mcxross.formation.mosaic.ui.PinchReader
import xyz.mcxross.formation.mosaic.ui.pieceLabel

class PinchReaderTest {
  private val state = MosaicGame(setup()).state
  // Position 0 is the top left phone: its seams are on its right and bottom edges.
  private val piece = Piece(state, phone(), 0)

  private fun mm(x: Double, y: Double) =
    Offset(piece.px(piece.box.left + x), piece.px(piece.box.top + y))

  @Test
  fun aSlideThatLiftsAtASeamIsHalfAPinch() {
    val reader = PinchReader(piece, state.grid)
    val pinch =
      assertNotNull(reader.read(mm(80.0, 30.0), mm(state.fragment.width - 3, 33.0), 1_000, 1_200))
    assertEquals(Edge.Right, pinch.edge)
    assertEquals(33.0, pinch.alongMm, 0.01)
    val down =
      assertNotNull(reader.read(mm(60.0, 20.0), mm(62.0, state.fragment.height + 4), 1_300, 1_400))
    assertEquals(Edge.Bottom, down.edge)
    assertEquals(62.0, down.alongMm, 0.01)
  }

  @Test
  fun outerEdgesShortDriftingAndEarlyLiftsAreIgnored() {
    val reader = PinchReader(piece, state.grid)
    assertNull(
      reader.read(mm(60.0, 30.0), mm(2.0, 30.0), 0, 100),
      "The left edge is the mosaic's outside",
    )
    assertNull(
      reader.read(mm(state.fragment.width - 8, 30.0), mm(state.fragment.width - 4, 30.0), 0, 100)
    )
    assertNull(
      reader.read(mm(96.0, 20.0), mm(136.0, 60.0), 0, 100),
      "A 45 degree slide picks no edge",
    )
    assertNull(
      reader.read(mm(20.0, 30.0), mm(state.fragment.width - 40, 30.0), 0, 100),
      "Lifted short of the seam",
    )
  }

  @Test
  fun pinchIdsKeepRisingWhenReleasesShareAMillisecond() {
    val reader = PinchReader(piece, state.grid)
    val first = assertNotNull(reader.read(mm(80.0, 30.0), mm(state.fragment.width, 30.0), 0, 5_000))
    val second =
      assertNotNull(reader.read(mm(60.0, 20.0), mm(60.0, state.fragment.height), 0, 5_000))
    assertTrue(second.id > first.id)
  }

  @Test
  fun piecesAreNamedByBarAndPart() {
    assertEquals("Top bar · left half", pieceLabel(state, 0))
    assertEquals("Middle bar · right half", pieceLabel(state, 3))
    val large = MosaicGame(setup(players = 18)).state
    assertEquals("Bottom bar · right end", pieceLabel(large, 17))
    assertEquals("Middle bar · piece 3 of 6", pieceLabel(large, 8))
  }
}
