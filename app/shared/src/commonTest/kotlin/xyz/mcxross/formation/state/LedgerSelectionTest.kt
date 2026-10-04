package xyz.mcxross.formation.state

import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerSelectionTest {
  @Test fun releaseAlwaysUsesTheChainEvenWithASavedSimulationPreference() {
    for (saved in listOf(null, "SIMULATED", "SOLANA", "unknown")) {
      assertEquals(LedgerMode.SOLANA, selectLedger(saved, debug = false))
    }
  }

  @Test fun debugSimulationRequiresAnExplicitSelection() {
    assertEquals(LedgerMode.SOLANA, selectLedger(null, debug = true))
    assertEquals(LedgerMode.SIMULATED, selectLedger("SIMULATED", debug = true))
    assertEquals(LedgerMode.SOLANA, selectLedger("unknown", debug = true))
  }
}
