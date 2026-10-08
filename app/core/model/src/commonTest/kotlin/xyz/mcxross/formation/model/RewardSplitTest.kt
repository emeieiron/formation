package xyz.mcxross.formation.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RewardSplitTest {
  // The vault's own tests pin the same splits.
  @Test
  fun theOwnerGetsWeightTimesEachHelper() {
    assertEquals(
      RewardSplit(Skr.of(300), Skr.of(225), Skr.of(75), 1),
      RewardSplit.of(Skr.of(300), 3, 1),
    )
    assertEquals(
      RewardSplit(Skr.of(300), Skr.of(150), Skr.of(50), 3),
      RewardSplit.of(Skr.of(300), 3, 3),
    )
    assertEquals(
      RewardSplit(Skr.of(300), Skr.of(75), Skr.of(25), 9),
      RewardSplit.of(Skr.of(300), 3, 9),
    )
  }

  @Test
  fun roundingDustGoesToTheOwner() {
    val split = RewardSplit.of(Skr(100), ownerWeight = 3, helpers = 4)
    assertEquals(Skr(14), split.helper)
    assertEquals(Skr(44), split.owner)
  }

  @Test
  fun largeAmountsDoNotOverflow() {
    val total = Skr(Long.MAX_VALUE)
    val split = RewardSplit.of(total, ownerWeight = 100, helpers = 64)
    assertEquals(total.units, split.owner.units + split.helper.units * 64)
  }

  @Test
  fun aSplitNeedsAnOwnerAndAHelper() {
    assertFailsWith<IllegalArgumentException> { RewardSplit.of(Skr.of(1), 0, 1) }
    assertFailsWith<IllegalArgumentException> { RewardSplit.of(Skr.of(1), 3, 0) }
  }

  @Test
  fun aBudgetFitsGroupsUpToItsGuestLimit() {
    val budget = Budget(OpportunityId("entry"), "contest", "sgt", Skr.of(300), 3, 27, 2_000, "Test")
    assertEquals(listOf(2, 28), (1..40).filter(budget::fits).let { listOf(it.first(), it.last()) })
    val opportunity = Opportunity(budget, ChallengeId("tap"), 3)
    assertEquals(Skr.of(60), opportunity.split().helper)
    assertFailsWith<IllegalArgumentException> { Opportunity(budget, ChallengeId("tap"), 29) }
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
}
