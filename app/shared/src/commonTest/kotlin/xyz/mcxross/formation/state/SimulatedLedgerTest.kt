package xyz.mcxross.formation.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import xyz.mcxross.formation.crypto.Base64
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.model.OpportunityId
import xyz.mcxross.formation.model.Skr
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.session.Seal

class SimulatedLedgerTest {
  @Test
  fun fixturesAreExplicitAndSettledRewardsDoNotReappearAfterRelaunch() = runTest {
    val values = mutableMapOf<String, String>()
    val store = object : KeyValueStore {
      override fun get(key: String) = values[key]
      override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    }
    val seeker = SeekerIdentity("host", null, true)
    val empty = SimulatedLedger(store)
    empty.refresh(seeker)
    assertTrue(empty.opportunities.value.isEmpty())
    values.clear()
    val reward = Opportunity(OpportunityId("0f8fad5b-d9cb-469f-a165-70867728950e"), ChallengeId("fixture"),
      Skr.of(120), 2, 5_000, Difficulty.EASY, Long.MAX_VALUE, "Test")
    val fixtures = listOf(reward)
    val ledger = SimulatedLedger(store, fixtures)
    assertEquals(fixtures, ledger.opportunities.value)
    ledger.unlock(seeker, reward, Seal(emptyList(), Skr.of(60), "", Base64.encode(byteArrayOf(1)), emptyList())).getOrThrow()
    val reopened = SimulatedLedger(store, fixtures)
    reopened.refresh(seeker)
    assertTrue(reopened.opportunities.value.isEmpty())
  }
}
