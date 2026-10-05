package xyz.mcxross.formation.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mcxross.formation.model.Budget
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.OpportunityId
import xyz.mcxross.formation.model.Skr

class CatalogRewardsTest {
  @Test fun previewOffersOnlyCurrentBudgetsThatFitTheGame() {
    val game = ChallengeId("mosaic")
    val current = Budget(OpportunityId("current"), "contest", "sgt", Skr.of(300), 3, 27, 2_000, "Test")
    val sooner = current.copy(id = OpportunityId("sooner"), playUntil = 1_500)
    val rewards = listOf(current, sooner, current.copy(playUntil = 1_000), current.copy(maxGuests = 4))
    assertEquals(listOf(sooner, current), ChallengeCatalog.rewardsFor(game, rewards, at = 1_000))
    assertEquals(listOf(6, 9), ChallengeCatalog.sizesFor(game, current))
    assertEquals(listOf(6), ChallengeCatalog.sizesFor(game, current.copy(maxGuests = 5)))
    assertTrue(ChallengeCatalog.rewardsFor(game, emptyList(), at = 1_000).isEmpty())
    assertTrue(ChallengeCatalog.rewardsFor(ChallengeId("removed"), rewards, at = 1_000).isEmpty())
  }
}
