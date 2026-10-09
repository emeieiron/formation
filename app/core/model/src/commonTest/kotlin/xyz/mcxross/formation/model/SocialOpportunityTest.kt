package xyz.mcxross.formation.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SocialOpportunityTest {
  @Test
  fun socialSessionsHaveAnIdentityButNoReward() {
    val session = Opportunity(null, ChallengeId("longshot"), 3, socialId = OpportunityId("room"))
    assertFalse(session.hasReward)
    assertEquals(OpportunityId("room"), session.id)
    assertEquals(Skr.ZERO, session.reward)
    assertFailsWith<IllegalArgumentException> { session.split() }
    assertFailsWith<IllegalArgumentException> { session.copy(socialId = null) }
    assertFailsWith<IllegalArgumentException> { session.copy(players = 33) }
    val budget = Budget(OpportunityId("entry"), "contest", "sgt", Skr.of(10), 1, 3, 999, "Sponsor")
    assertFailsWith<IllegalArgumentException> { session.copy(budget = budget) }
  }
}
