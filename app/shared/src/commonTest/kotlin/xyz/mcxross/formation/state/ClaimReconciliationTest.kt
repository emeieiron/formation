package xyz.mcxross.formation.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.mcxross.formation.model.*

class ClaimReconciliationTest {
  private val ticket =
    ClaimTicket(
      OpportunityId("So11111111111111111111111111111111111111112"),
      "11111111111111111111111111111111",
      ChallengeId("sync"),
      "Host",
      Skr.of(60),
      0,
      "root",
      emptyList(),
      1,
      "unlock",
    )
  private val chain = ClaimChainState(true, 100, "root", 1, ticket.amount.units, false, 1_000)

  @Test
  fun theClaimWindowClosesEvenWhenTheUnlockedAccountStillExists() {
    assertFalse(reconcileClaim(ticket, chain, 1_000).lapsed)
    val expired = reconcileClaim(ticket, chain, 1_001)
    assertTrue(expired.lapsed)
    assertEquals(1_000L, expired.claimDeadline)
    assertTrue(reconcileClaim(ticket, null, 1_001).lapsed)
    val paid = reconcileClaim(ticket, chain.copy(claimed = true), 1_001, "original-recipient")
    assertTrue(paid.claimed)
    assertFalse(paid.lapsed)
    assertEquals("original-recipient", paid.claimedTo)
  }

  @Test
  fun anotherRosterOrAmountCannotMakeASavedProofClaimable() {
    assertFailsWith<IllegalArgumentException> {
      reconcileClaim(ticket, chain.copy(root = "other"), 1)
    }
    assertFailsWith<IllegalArgumentException> { reconcileClaim(ticket, chain.copy(share = 1), 1) }
  }
}
