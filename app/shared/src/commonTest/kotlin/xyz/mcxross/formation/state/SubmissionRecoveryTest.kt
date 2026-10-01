package xyz.mcxross.formation.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.state.settlement.*

class SubmissionRecoveryTest {
  private class Store : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun get(key: String) = values[key]
    override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
  }
  private class Node : SubmissionTransport {
    var status: TransactionStatus? = null
    var height = 1L
    var broadcasts = 0
    var offline = false
    override suspend fun status(signature: String) = status
    override suspend fun blockHeight() = height
    override suspend fun broadcast(signed: ByteArray): String {
      broadcasts++
      if (offline) error("Network disappeared after submission")
      return signedTransactionId(signed)
    }
  }
  private val signed = byteArrayOf(1) + ByteArray(64) { 7 } + ByteArray(20)

  @Test fun aLostResponseIsReconciledAfterRestartWithoutBroadcastingAgain() = runTest {
    val store = Store()
    val journal = SubmissionJournal(store, "tx")
    val saved = journal.prepare("unlock:one", signed, 100)
    val node = Node().apply { offline = true }
    assertFailsWith<IllegalStateException> { SubmissionRunner(journal, node).execute(saved) }
    node.offline = false
    node.status = TransactionStatus(confirmed = true)
    val restored = SubmissionJournal(store, "tx")
    val runner = SubmissionRunner(restored, node)
    runner.resumePending()
    assertEquals(saved.signature, runner.execute(restored.latest("unlock:one")!!))
    assertEquals(1, node.broadcasts)
  }

  @Test fun timeoutRemainsPendingAndExpiryRequiresBlockHeightEvidence() = runTest {
    val journal = SubmissionJournal(Store(), "tx")
    val saved = journal.prepare("claim:one:0", signed, 10)
    val node = Node()
    val runner = SubmissionRunner(journal, node, timeoutMs = 20, pollMs = 5)
    assertFailsWith<IllegalStateException> { runner.execute(saved) }
    assertEquals(SubmissionState.PENDING, journal.latest(saved.operation)!!.state)
    node.height = 10
    assertEquals(SubmissionState.PENDING, runner.reconcile(saved).state)
    node.height = 11
    assertEquals(SubmissionState.EXPIRED, runner.reconcile(saved).state)
  }

  @Test fun oneConfirmedBatchDoesNotHideAnUnsettledBatch() = runTest {
    val store = Store()
    val journal = SubmissionJournal(store, "tx")
    val first = journal.prepare("unlock:one", signed, 100)
    val second = journal.prepare("payout:one:1", signed.copyOf().also { it[1] = 8 }, 100)
    journal.update(first.copy(state = SubmissionState.CONFIRMED))
    assertEquals(listOf(second.signature), SubmissionJournal(store, "tx").pending().map { it.signature })
  }
}
