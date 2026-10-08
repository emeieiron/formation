package xyz.mcxross.formation.mosaic

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import xyz.mcxross.formation.model.PlayerId

class DealTest {
  private val players = (1..6).map { PlayerId("p$it") }

  @Test
  fun everyPhoneGetsOnePositionAndTheSeedRepeatsTheDeal() {
    val screens = players.associateWith { phone() }
    val grid = Layouts.grid(6)
    val fragment = Layouts.fragment(screens.values, grid.placement)
    val first = Deal.deal(players, screens, grid, fragment, Random(3))
    assertEquals(players.toSet(), first.toSet())
    assertEquals(first, Deal.deal(players, screens, grid, fragment, Random(3)))
    val others = (4L..12L).map { Deal.deal(players, screens, grid, fragment, Random(it)) }
    assertTrue(others.any { it != first }, "Identical phones still shuffle between seeds")
  }

  @Test
  fun phonesClosestToTheFragmentTakeTheRowThatCannotPushOutward() {
    // In landscape the middle row centres its fragment vertically, so tall spare margins would show
    // there.
    val wide = players.take(4).associateWith { phone(shortMm = 74.0, longMm = 150.0) }
    val snug = players.drop(4).associateWith { phone(shortMm = 62.0, longMm = 150.0) }
    val screens = wide + snug
    val grid = Layouts.grid(6)
    val fragment = Layouts.fragment(screens.values, grid.placement)
    repeat(20) { seed ->
      val dealt = Deal.deal(players, screens, grid, fragment, Random(seed))
      assertEquals(
        snug.keys,
        setOf(dealt[2], dealt[3]),
        "Seed $seed put a wide phone in the middle row",
      )
    }
  }

  @Test
  fun aLargeGroupStillPlacesEveryone() {
    val roster = (1..18).map { PlayerId("p$it") }
    val screens = roster.associateWith {
      phone(60.0 + it.value.drop(1).toInt() % 5, 130.0 + it.value.drop(1).toInt() % 7)
    }
    val grid = Layouts.grid(18)
    val dealt =
      Deal.deal(roster, screens, grid, Layouts.fragment(screens.values, grid.placement), Random(9))
    assertEquals(roster.toSet(), dealt.toSet())
    assertNotEquals(roster, dealt)
  }
}
