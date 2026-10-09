package xyz.mcxross.formation.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mcxross.formation.caravan.Caravan
import xyz.mcxross.formation.longshot.Longshot
import xyz.mcxross.formation.model.Budget
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.OpportunityId
import xyz.mcxross.formation.model.Skr

class CatalogRewardsTest {
  @Test
  fun longshotAndCaravanCoexistWithIndependentCodesAndRewardModes() {
    assertEquals(5, ChallengeCatalog.all.size)
    assertEquals(Longshot, ChallengeCatalog.byCode(9))
    assertEquals(Caravan, ChallengeCatalog.byCode(10))
    assertEquals(ChallengeCatalog.all.size, ChallengeCatalog.all.map { it.info.code }.toSet().size)
    val budget = Budget(OpportunityId("entry"), "contest", "sgt", Skr.of(300), 3, 27, 2000, "Test")
    val walk = xyz.mcxross.formation.model.Opportunity(budget, Caravan.id, 6)
    assertTrue(ChallengeCatalog.supports(walk))
    assertEquals(listOf(6, 7, 8), ChallengeCatalog.sizesFor(Caravan.id, budget.copy(maxGuests = 7)))
    assertTrue(ChallengeCatalog.rewardsFor(Longshot.id, listOf(budget), 1000).isEmpty())
  }

  @Test
  fun longshotIsAvailableWithoutAcceptingSponsorBudgets() {
    val game = xyz.mcxross.formation.longshot.Longshot
    val social =
      xyz.mcxross.formation.model.Opportunity(null, game.id, 3, socialId = OpportunityId("room"))
    val budget = Budget(OpportunityId("entry"), "contest", "sgt", Skr.of(300), 3, 27, 2000, "Test")
    assertTrue(ChallengeCatalog.supports(social))
    assertEquals(false, ChallengeCatalog.supports(social.copy(budget = budget, socialId = null)))
    assertEquals(
      false,
      ChallengeCatalog.supports(social.copy(challenge = ChallengeId("overdrive"), players = 2)),
    )
    assertTrue(ChallengeCatalog.rewardsFor(game.id, listOf(budget), 1000).isEmpty())
  }

  @Test
  fun previewOffersOnlyCurrentBudgetsThatFitTheGame() {
    val game = ChallengeId("mosaic")
    val current =
      Budget(OpportunityId("current"), "contest", "sgt", Skr.of(300), 3, 27, 2_000, "Test")
    val sooner = current.copy(id = OpportunityId("sooner"), playUntil = 1_500)
    val rewards =
      listOf(current, sooner, current.copy(playUntil = 1_000), current.copy(maxGuests = 4))
    assertEquals(listOf(sooner, current), ChallengeCatalog.rewardsFor(game, rewards, at = 1_000))
    assertEquals(listOf(6, 9), ChallengeCatalog.sizesFor(game, current))
    assertEquals(listOf(6), ChallengeCatalog.sizesFor(game, current.copy(maxGuests = 5)))
    assertTrue(ChallengeCatalog.rewardsFor(game, emptyList(), at = 1_000).isEmpty())
    assertTrue(ChallengeCatalog.rewardsFor(ChallengeId("removed"), rewards, at = 1_000).isEmpty())
  }
}
