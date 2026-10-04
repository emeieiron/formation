package xyz.mcxross.formation.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.model.OpportunityId
import xyz.mcxross.formation.model.OpportunityState
import xyz.mcxross.formation.model.Skr

class CatalogRewardsTest {
  @Test fun previewOffersOnlyCurrentRewardsForASupportedGame() {
    val game = ChallengeId("ricochet")
    val current = Opportunity(OpportunityId("current"), game, Skr.of(120), 2, 5_000, Difficulty.EASY, 2_000, "Test")
    val sooner = current.copy(id = OpportunityId("sooner"), expiresAt = 1_500)
    val rewards = listOf(current, sooner,
      current.copy(expiresAt = 1_000),
      current.copy(state = OpportunityState.UNLOCKED),
      current.copy(state = OpportunityState.EXPIRED),
      current.copy(challenge = ChallengeId("overdrive")),
      current.copy(players = 3),
    )
    assertEquals(listOf(sooner, current), ChallengeCatalog.rewardsFor(game, rewards, at = 1_000))
    assertTrue(ChallengeCatalog.rewardsFor(game, emptyList(), at = 1_000).isEmpty())
    assertTrue(ChallengeCatalog.rewardsFor(ChallengeId("removed"), rewards, at = 1_000).isEmpty())
  }
}
