package xyz.mcxross.formation.state.recovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Base64
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.platform.SecretStore
import xyz.mcxross.formation.session.FormationJson
import xyz.mcxross.formation.state.ClaimTicket
import xyz.mcxross.formation.state.RewardLedger

class RecoveryService(
  private val identity: ClaimIdentity,
  private val ledger: RewardLedger,
  private val store: KeyValueStore,
  private val secrets: SecretStore,
  private val cluster: String,
  private val replacementAllowed: () -> Boolean,
  private val cipher: RecoveryCipher = recoveryCipher(),
) {
  val available get() = cipher.available
  private val lock = Mutex()

  suspend fun export(password: String): String = withContext(Dispatchers.Default) { lock.withLock {
    require(password.length in 12..256) { "Use a recovery password of at least 12 characters" }
    val key = identity.key
    val bundle = RecoveryBundle(cluster = cluster, mode = ledger.mode, seed = Base64.encode(key.seed),
      address = Base58.encode(key.publicKey), tickets = ledger.tickets.value)
    bundle.validate(cluster, ledger.mode)
    val plain = FormationJson.encodeToString(RecoveryBundle.serializer(), bundle).encodeToByteArray()
    try { PREFIX + Base64.encode(cipher.encrypt(plain, password)) } finally { plain.fill(0) }
  } }

  suspend fun import(text: String, password: String): Unit = withContext(Dispatchers.Default) { lock.withLock {
    require(text.length <= 1_400_000 && text.trim().startsWith(PREFIX)) { "Paste a Formation recovery export" }
    require(password.length in 1..256) { "Enter the recovery password" }
    val plain = try { cipher.decrypt(Base64.decode(text.trim().removePrefix(PREFIX)), password) }
      catch (_: Exception) { error("The password or recovery data is incorrect") }
    val bundle = try { FormationJson.decodeFromString(RecoveryBundle.serializer(), plain.decodeToString()) }
      finally { plain.fill(0) }
    val key = bundle.validate(cluster, ledger.mode)
    val matchingLegacyProofs = identity.address == null && ledger.tickets.value.isNotEmpty() &&
      runCatching { validateTickets(ledger.tickets.value, key) }.isSuccess
    check(identity.address == bundle.address || matchingLegacyProofs || replacementAllowed()) { "This phone has rewards belonging to a different claim key" }
    // Validate conflicts before making any identity change.
    bundle.tickets.forEach { incoming ->
      val existing = ledger.tickets.value.firstOrNull { it.opportunity == incoming.opportunity && it.index == incoming.index }
      require(existing == null || existing.root == incoming.root) { "A saved reward has a different commitment" }
    }
    secrets.put(STAGED_KEY, key.seed)
    store.putDurable(PENDING, FormationJson.encodeToString(PendingRecovery.serializer(),
      PendingRecovery(bundle.address, bundle.tickets, cluster, ledger.mode.name)))
    finish(PendingRecovery(bundle.address, bundle.tickets, cluster, ledger.mode.name), key)
  } }

  // Idempotent: if the process died between the key and reward writes, finish that exact restore.
  fun resumePending() {
    val pending = store.get(PENDING)?.let { FormationJson.decodeFromString(PendingRecovery.serializer(), it) } ?: return
    require(pending.cluster == cluster && pending.mode == ledger.mode.name) { "Switch back to the recovery export's ledger to finish restoring" }
    val key = Ed25519KeyPair.fromSeed(secrets.get(STAGED_KEY) ?: error("The staged recovery key is unavailable"))
    require(Base58.encode(key.publicKey) == pending.address) { "The staged recovery key is invalid" }
    validateTickets(pending.tickets, key)
    finish(pending, key)
  }

  private fun finish(pending: PendingRecovery, key: Ed25519KeyPair) {
    identity.restore(key)
    ledger.restore(pending.tickets)
    store.putDurable(PENDING, null)
    secrets.remove(STAGED_KEY)
  }

  @Serializable
  private data class PendingRecovery(val address: String, val tickets: List<ClaimTicket>, val cluster: String, val mode: String)
  private companion object {
    const val PREFIX = "formation-recovery:1:"
    const val PENDING = "claim.restore.pending"
    const val STAGED_KEY = "claim-restore"
  }
}
