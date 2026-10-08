package xyz.mcxross.formation.state.settlement

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mcxross.formation.crypto.Base64
import xyz.mcxross.formation.session.DiagnosticCode
import xyz.mcxross.formation.session.DiagnosticEvent
import xyz.mcxross.formation.solana.RpcException
import xyz.mcxross.formation.solana.SolanaRpc

data class TransactionStatus(val confirmed: Boolean, val failure: String? = null)

interface SubmissionTransport {
  suspend fun status(signature: String): TransactionStatus?

  suspend fun blockHeight(): Long

  suspend fun broadcast(signed: ByteArray): String
}

class RpcSubmissionTransport(private val rpc: SolanaRpc) : SubmissionTransport {
  override suspend fun status(signature: String) =
    rpc.signatureStatus(signature, history = true)?.let {
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
  private val observe: (DiagnosticEvent) -> Unit = {},
) {
  private fun update(entry: Submission): Submission {
    val saved = journal.update(entry)
    val code =
      when (saved.state) {
        SubmissionState.PENDING -> DiagnosticCode.SUBMISSION_PENDING
        SubmissionState.CONFIRMED -> DiagnosticCode.SUBMISSION_CONFIRMED
        SubmissionState.FAILED -> DiagnosticCode.SUBMISSION_FAILED
        SubmissionState.EXPIRED -> DiagnosticCode.SUBMISSION_EXPIRED
      }
    observe(DiagnosticEvent(code))
    return saved
  }

  suspend fun reconcile(entry: Submission): Submission {
    if (entry.state != SubmissionState.PENDING) return entry
    val status = transport.status(entry.signature)
    return when {
      status?.failure != null ->
        update(entry.copy(state = SubmissionState.FAILED, problem = status.failure))
      status?.confirmed == true ->
        update(entry.copy(state = SubmissionState.CONFIRMED, problem = null))
      transport.blockHeight() > entry.validUntil -> {
        // A signature may have landed between the first status read and the height read.
        val finalStatus = transport.status(entry.signature)
        when {
          finalStatus?.confirmed == true ->
            update(entry.copy(state = SubmissionState.CONFIRMED, problem = null))
          finalStatus?.failure != null ->
            update(entry.copy(state = SubmissionState.FAILED, problem = finalStatus.failure))
          finalStatus == null -> update(entry.copy(state = SubmissionState.EXPIRED))
          else -> if (entry.observed) entry else update(entry.copy(observed = true))
        }
      }
      status != null && !entry.observed -> update(entry.copy(observed = true))
      else -> entry
    }
  }

  suspend fun resumePending() {
    for (entry in journal.pending()) {
      try {
        val current = reconcile(entry)
        if (current.state == SubmissionState.PENDING && !current.observed)
          transport.broadcast(Base64.decode(current.transaction))
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        observe(DiagnosticEvent(DiagnosticCode.SUBMISSION_UNCERTAIN))
      }
    }
  }

  suspend fun execute(entry: Submission): String {
    observe(DiagnosticEvent(DiagnosticCode.SUBMISSION_PENDING))
    val current = reconcile(entry)
    if (current.state == SubmissionState.CONFIRMED) return current.signature
    check(current.state == SubmissionState.PENDING) {
      current.problem ?: "The transaction expired. Try again."
    }
    try {
      if (!current.observed)
        check(transport.broadcast(Base64.decode(current.transaction)) == current.signature) {
          "Unexpected transaction receipt"
        }
    } catch (e: CancellationException) {
      throw e
    } catch (e: RpcException) {
      // A preflight rejection is definite. A network failure leaves the transaction uncertain.
      if (e.code == -32002)
        update(current.copy(state = SubmissionState.FAILED, problem = e.describe()))
      if (e.code != -32002) observe(DiagnosticEvent(DiagnosticCode.SUBMISSION_UNCERTAIN))
      throw e
    } catch (e: Exception) {
      observe(DiagnosticEvent(DiagnosticCode.SUBMISSION_UNCERTAIN))
      throw e
    }
    val confirmed =
      withTimeoutOrNull(timeoutMs) {
        while (true) {
          val next = reconcile(current)
          if (next.state == SubmissionState.CONFIRMED) return@withTimeoutOrNull next.signature
          check(next.state == SubmissionState.PENDING) {
            next.problem ?: "The transaction expired. Try again."
          }
          delay(pollMs)
        }
        @Suppress("UNREACHABLE_CODE") null
      }
    if (confirmed == null) observe(DiagnosticEvent(DiagnosticCode.SUBMISSION_UNCERTAIN))
    return confirmed
      ?: error(
        "Awaiting confirmation. Your signed transaction is saved; check again before retrying."
      )
  }
}
