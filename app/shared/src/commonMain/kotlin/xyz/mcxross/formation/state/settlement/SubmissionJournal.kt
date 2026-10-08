package xyz.mcxross.formation.state.settlement

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Base64
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.session.FormationJson

@Serializable
enum class SubmissionState {
  PENDING,
  CONFIRMED,
  FAILED,
  EXPIRED,
}

@Serializable
data class Submission(
  val operation: String,
  val signature: String,
  val transaction: String,
  val validUntil: Long,
  val state: SubmissionState = SubmissionState.PENDING,
  val problem: String? = null,
  val recipient: String? = null,
  val observed: Boolean = false,
)

class SubmissionJournal(private val store: KeyValueStore, private val key: String) {
  private var entries: List<Submission> =
    store.get(key)?.let {
      FormationJson.decodeFromString(ListSerializer(Submission.serializer()), it)
    } ?: emptyList()

  fun latest(operation: String): Submission? = entries.lastOrNull { it.operation == operation }

  fun pending(): List<Submission> = entries.filter { it.state == SubmissionState.PENDING }

  fun prepare(
    operation: String,
    signed: ByteArray,
    validUntil: Long,
    recipient: String? = null,
  ): Submission {
    val previous = latest(operation)
    check(previous?.state != SubmissionState.PENDING) {
      "A transaction is still awaiting confirmation"
    }
    val entry =
      Submission(
        operation,
        signedTransactionId(signed),
        Base64.encode(signed),
        validUntil,
        recipient = recipient,
      )
    save(
      (entries.filterNot { it.operation == operation && it.state != SubmissionState.PENDING }) +
        entry
    )
    return entry
  }

  fun update(entry: Submission): Submission {
    save(entries.map { if (it.signature == entry.signature) entry else it })
    return entry
  }

  private fun save(next: List<Submission>) {
    store.putDurable(
      key,
      FormationJson.encodeToString(ListSerializer(Submission.serializer()), next),
    )
    entries = next
  }
}

// Solana's compact signature count precedes the first 64-byte signature.
internal fun signedTransactionId(bytes: ByteArray): String {
  var offset = 0
  var count = 0
  var shift = 0
  do {
    require(offset < bytes.size && shift <= 14) { "Malformed signed transaction" }
    val b = bytes[offset++].toInt() and 255
    count = count or ((b and 127) shl shift)
    shift += 7
  } while (b and 128 != 0)
  require(count in 1..64 && bytes.size >= offset + count * 64) { "Missing transaction signature" }
  val signature = bytes.copyOfRange(offset, offset + 64)
  require(signature.any { it != 0.toByte() }) { "The fee payer did not sign" }
  return Base58.encode(signature)
}
