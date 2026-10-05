package xyz.mcxross.formation.state

import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerSelectionTest {
  @Test fun releaseAlwaysUsesTheChainEvenWithASavedSimulationPreference() {
    for (saved in listOf(null, "SIMULATED", "SOLANA", "unknown")) {
      assertEquals(LedgerMode.SOLANA, selectLedger(saved, developer = false))
    }
  }

  @Test fun debugSimulationRequiresAnExplicitSelection() {
    assertEquals(LedgerMode.SOLANA, selectLedger(null, developer = true))
    assertEquals(LedgerMode.SIMULATED, selectLedger("SIMULATED", developer = true))
    assertEquals(LedgerMode.SOLANA, selectLedger("unknown", developer = true))
  }
}
