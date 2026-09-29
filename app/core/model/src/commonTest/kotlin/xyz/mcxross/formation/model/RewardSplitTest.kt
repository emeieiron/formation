package xyz.mcxross.formation.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RewardSplitTest {
  @Test
  fun halfToTheOwnerQuarterEachToFourHelpers() {
    val split = RewardSplit.of(Skr.of(600), ownerBps = 5_000, helpers = 4)
    assertEquals(Skr.of(300), split.owner)
    assertEquals(Skr.of(75), split.helper)
  }

  @Test
  fun roundingDustGoesToTheOwner() {
    val split = RewardSplit.of(Skr(100), ownerBps = 5_000, helpers = 3)
    assertEquals(Skr(16), split.helper)
    assertEquals(Skr(52), split.owner)
  }

  @Test
  fun largeAmountsDoNotOverflow() {
    val total = Skr(Long.MAX_VALUE / 2)
    val split = RewardSplit.of(total, ownerBps = 9_999, helpers = 7)
    assertEquals(total.units, split.owner.units + split.helper.units * 7)
  }

  @Test
  fun theOwnerCannotTakeEverything() {
    assertFailsWith<IllegalArgumentException> { RewardSplit.of(Skr.of(1), 10_000, 1) }
  }

  @Test
  fun formatsGroupedAndTrimmed() {
    assertEquals("1,500", Skr.of(1_500).format())
    assertEquals("75.5", Skr(75_500_000).format())
    assertEquals("0.000001", Skr(1).format())
    assertEquals("12,345,678.9", Skr(12_345_678_900_000).format())
  }

  @Test
  fun tiersFollowGroupSize() {
    assertEquals(Tier.DUO, Tier.of(2))
    assertEquals(Tier.SQUAD, Tier.of(5))
    assertEquals(Tier.CREW, Tier.of(10))
    assertEquals(Tier.LEGENDARY, Tier.of(20))
  }

  @Test
  fun uuidBytesRoundTrip() {
    val id = OpportunityId("0f8fad5b-d9cb-469f-a165-70867728950e")
    assertEquals(16, id.bytes().size)
    assertEquals(0x0f, id.bytes()[0].toInt())
    assertEquals(0x0e, id.bytes()[15].toInt())
  }
}
