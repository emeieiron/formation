package xyz.mcxross.formation.mosaic

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.mcxross.formation.session.ScreenInsets
import xyz.mcxross.formation.session.ScreenProfile

class LayoutTest {
  @Test
  fun artworkHasThreeEqualBarsSeparatedByEqualGaps() {
    assertEquals(3, Artwork.outlines.size)
    val bars = Artwork.bars
    assertTrue(abs(bars[0].start) < 0.05 && abs(bars[2].endInclusive - Artwork.HEIGHT) < 0.05)
    val heights = bars.map { it.endInclusive - it.start }
    assertTrue(heights.all { abs(it - 21.79) < 0.05 }, "Bar heights $heights")
    assertTrue(
      abs((bars[1].start - bars[0].endInclusive) - (bars[2].start - bars[1].endInclusive)) < 0.05
    )
    assertTrue(Artwork.contains(Vec(50.0, 11.0)))
    assertFalse(Artwork.contains(Vec(50.0, 27.0)))
    assertFalse(
      Artwork.contains(Vec(2.0, 2.0)),
      "The top bar's slanted end leaves its corner empty",
    )
  }

  @Test
  fun theFragmentTakesTheNarrowestWidthAndTheShortestHeight() {
    val phones = listOf(phone(64.0, 140.0), phone(58.0, 146.0), phone(68.0, 128.0))
    assertEquals(Size(58.0, 128.0), Layouts.fragment(phones, Placement.Portrait))
    assertEquals(Size(128.0, 58.0), Layouts.fragment(phones, Placement.Landscape))
  }

  @Test
  fun landscapePhonesLieWithTheirTopEdgeToTheLeft() {
    val profile =
      ScreenProfile(
        70.0,
        150.0,
        16.0,
        ScreenInsets(left = 1.0, top = 4.0, right = 2.0, bottom = 3.0),
      )
    assertEquals(Box(4.0, 2.0, 143.0, 67.0), Layouts.usable(profile, Placement.Landscape))
    assertEquals(Box(1.0, 4.0, 67.0, 143.0), Layouts.usable(profile, Placement.Portrait))
  }

  @Test
  fun fragmentsMoveTowardTheSeamsTheyCanReach() {
    val canvas = Canvas(Layouts.grid(9), Size(120.0, 60.0), Layouts.gaps(Placement.Landscape))
    val usable = Box(5.0, 2.0, 140.0, 66.0)
    assertEquals(
      Box(25.0, 8.0, 120.0, 60.0),
      canvas.placement(0, usable),
      "Top left pushes right and down",
    )
    assertEquals(
      Box(15.0, 5.0, 120.0, 60.0),
      canvas.placement(4, usable),
      "The centre phone centres both ways",
    )
    assertEquals(
      Box(5.0, 2.0, 120.0, 60.0),
      canvas.placement(8, usable),
      "Bottom right pushes left and up",
    )
  }

  @Test
  fun everySupportedSizeGivesEachPhoneAPieceAndKeepsBarsOutOfTheSeams() {
    val floors = mapOf(6 to 0.40, 9 to 0.06, 18 to 0.20)
    for ((players, floor) in floors) for (tenths in 16..24) {
      val short = 62.0
      val profile = phone(short, short * tenths / 10)
      val grid = Layouts.grid(players)
      val canvas =
        Canvas(
          grid,
          Layouts.fragment(listOf(profile), grid.placement),
          Layouts.gaps(grid.placement),
        )
      val lowest = (0 until grid.size).minOf { coverage(canvas, it) }
      assertTrue(
        lowest >= floor,
        "$players phones at ${tenths / 10.0}:1 leave a fragment ${lowest * 100}% filled",
      )
      // Each bar lies inside one row's windows, so no seam band crosses a bar.
      Artwork.bars.forEachIndexed { row, bar ->
        val window = canvas.window(row * grid.columns)
        val top = canvas.toCanvas(Vec(0.0, bar.start)).y
        val bottom = canvas.toCanvas(Vec(0.0, bar.endInclusive)).y
        assertTrue(
          top >= window.top - 0.01 && bottom <= window.bottom + 0.01,
          "$players phones at ${tenths / 10.0}:1 put bar $row across a row seam",
        )
      }
    }
  }

  @Test
  fun theQuietStripLeavesRoomForTheHudBesideEveryBar() {
    for (players in Layouts.sizes) {
      val grid = Layouts.grid(players)
      val canvas =
        Canvas(
          grid,
          Layouts.fragment(listOf(phone()), grid.placement),
          Layouts.gaps(grid.placement),
        )
      for (position in 0 until grid.size) {
        val strip = canvas.quietStrip(position)
        assertTrue(
          strip.height >= 4.0,
          "$players phones leave only ${strip.height} mm at position $position",
        )
        val window = canvas.window(position)
        val middle =
          Vec(window.left + strip.left + strip.width / 2, window.top + strip.top + strip.height / 2)
        assertFalse(Artwork.contains(canvas.toArtwork(middle)))
      }
    }
  }

  @Test
  fun seamsJoinEveryNeighbourOnce() {
    assertEquals(7, Layouts.grid(6).seams().size)
    assertEquals(12, Layouts.grid(9).seams().size)
    assertEquals(27, Layouts.grid(18).seams().size)
    val grid = Layouts.grid(6)
    grid.seams().forEach { seam ->
      val edge = seam.edgeOf(seam.first)!!
      assertEquals(seam.second, grid.neighbour(seam.first, edge))
      assertEquals(edge.opposite, seam.edgeOf(seam.second))
    }
    assertEquals(null, grid.neighbour(0, Edge.Left))
    assertEquals(null, grid.neighbour(5, Edge.Bottom))
    assertFailsWith<IllegalArgumentException> { Layouts.grid(12) }
  }
}
