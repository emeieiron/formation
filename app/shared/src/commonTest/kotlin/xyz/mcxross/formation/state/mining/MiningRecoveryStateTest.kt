package xyz.mcxross.formation.state.mining

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiningRecoveryStateTest {
  private val position = MiningPosition("room/1/1", "wallet-a", 7, 17, 1_000_000)

  @Test
  fun unsignedFailedAndSettledAttemptsDoNotCreateRecovery() {
    listOf(
        position,
        position.copy(signature = "sig", status = MiningStatus.Failed),
        position.copy(signature = "sig", status = MiningStatus.Settled),
      )
      .forEach {
        val state = MiningAccountState(wallet = "wallet-a", positions = listOf(it))
        assertFalse(state.needsAttention)
        assertFalse(state.canRecover)
      }
  }

  @Test
  fun switchingWalletDoesNotHideAnOutstandingSignedTransaction() {
    val state =
      MiningAccountState(
        wallet = "wallet-b",
        positions = listOf(position.copy(signature = "sig", status = MiningStatus.Submitted)),
      )
    assertTrue(state.needsAttention)
    assertFalse(state.pendingSubmission)
    assertFalse(state.canRecover)
  }

  @Test
  fun pendingDrawCannotStartRecoveryEvenWithOlderRewards() {
    val state =
      MiningAccountState(
        wallet = "wallet-a",
        needsCheckpoint = true,
        awaitingDraw = true,
        claimableSol = 891_000u,
      )
    assertTrue(state.needsAttention)
    assertFalse(state.canRecover)
    assertTrue(state.copy(awaitingDraw = false).canRecover)
  }

  @Test
  fun onChainProceedsAreRecoverableWithoutLocalHistory() {
    val state = MiningAccountState(wallet = "wallet-a", claimableOre = 1u)
    assertTrue(state.needsAttention)
    assertTrue(state.canRecover)
    assertFalse(state.copy(claimableOre = 0u).needsAttention)
  }
}
