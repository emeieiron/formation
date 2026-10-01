package xyz.mcxross.formation.state.settlement

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mcxross.formation.crypto.Base64
import xyz.mcxross.formation.solana.RpcException
import xyz.mcxross.formation.solana.SolanaRpc

data class TransactionStatus(val confirmed: Boolean, val failure: String? = null)

interface SubmissionTransport {
  suspend fun status(signature: String): TransactionStatus?
  suspend fun blockHeight(): Long
  suspend fun broadcast(signed: ByteArray): String
}

class RpcSubmissionTransport(private val rpc: SolanaRpc) : SubmissionTransport {
  override suspend fun status(signature: String) = rpc.signatureStatus(signature, history = true)?.let {
    TransactionStatus(it.reached("confirmed"), it.error?.toString())
  }
  override suspend fun blockHeight() = rpc.blockHeight()
  override suspend fun broadcast(signed: ByteArray) = rpc.sendTransaction(signed)
}

class SubmissionRunner(
  private val journal: SubmissionJournal,
  private val transport: SubmissionTransport,
  private val timeoutMs: Long = 60_000,
  private val pollMs: Long = 600,
) {
  suspend fun reconcile(entry: Submission): Submission {
    if (entry.state != SubmissionState.PENDING) return entry
    val status = transport.status(entry.signature)
    return when {
      status?.failure != null -> journal.update(entry.copy(state = SubmissionState.FAILED, problem = status.failure))
      status?.confirmed == true -> journal.update(entry.copy(state = SubmissionState.CONFIRMED, problem = null))
      transport.blockHeight() > entry.validUntil -> journal.update(entry.copy(state = SubmissionState.EXPIRED))
      else -> entry
    }
  }

  suspend fun resumePending() {
    for (entry in journal.pending()) {
      try {
        val current = reconcile(entry)
        if (current.state == SubmissionState.PENDING)
          transport.broadcast(Base64.decode(current.transaction))
      } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Keep uncertain work. */ }
    }
  }

  suspend fun execute(entry: Submission): String {
    val current = reconcile(entry)
    if (current.state == SubmissionState.CONFIRMED) return current.signature
    check(current.state == SubmissionState.PENDING) { current.problem ?: "The transaction expired. Try again." }
    try {
      check(transport.broadcast(Base64.decode(current.transaction)) == current.signature) { "Unexpected transaction receipt" }
    } catch (e: CancellationException) {
      throw e
    } catch (e: RpcException) {
      // A preflight rejection is definite. A network failure leaves the transaction uncertain.
      if (e.code == -32002) journal.update(current.copy(state = SubmissionState.FAILED, problem = e.describe()))
      throw e
    }
    val confirmed = withTimeoutOrNull(timeoutMs) {
      while (true) {
        val next = reconcile(current)
        if (next.state == SubmissionState.CONFIRMED) return@withTimeoutOrNull next.signature
        check(next.state == SubmissionState.PENDING) { next.problem ?: "The transaction expired. Try again." }
        delay(pollMs)
      }
      @Suppress("UNREACHABLE_CODE") null
    }
    return confirmed ?: error("Awaiting confirmation. Your signed transaction is saved; check again before retrying.")
  }
}
