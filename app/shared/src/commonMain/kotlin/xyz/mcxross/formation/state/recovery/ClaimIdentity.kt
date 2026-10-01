package xyz.mcxross.formation.state.recovery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.platform.SecretStore

sealed interface ClaimKeyState {
  data class Ready(val address: String) : ClaimKeyState
  data class Missing(val address: String?) : ClaimKeyState
}

class ClaimIdentity(private val store: KeyValueStore, private val secrets: SecretStore, knownAddress: () -> String? = { null }) {
  private var cached: Ed25519KeyPair? = null
  private val state = MutableStateFlow<ClaimKeyState>(ClaimKeyState.Missing(store.get(PUBLIC) ?: knownAddress()))
  val status = state.asStateFlow()

  init {
    val seed = runCatching { secrets.get(SECRET) }.getOrNull()
    val savedAddress = store.get(PUBLIC) ?: knownAddress()
    val key = seed?.takeIf { it.size == 32 }?.let(Ed25519KeyPair::fromSeed)
    when {
      key != null && (savedAddress == null || savedAddress == Base58.encode(key.publicKey)) -> runCatching { install(key) }
      key != null || savedAddress != null || secrets.contains(SECRET) || hasRecords() -> Unit
      else -> runCatching { install(Ed25519KeyPair.generate()) }
    }
  }

  val key: Ed25519KeyPair get() = cached ?: error("Restore this phone's claim key before continuing")
  val address: String? get() = when (val current = state.value) {
    is ClaimKeyState.Ready -> current.address
    is ClaimKeyState.Missing -> current.address
  }

  fun restore(key: Ed25519KeyPair) = install(key)

  private fun hasRecords() = listOf("sol.tickets", "sim.tickets", "sessions.completed").any {
    store.get(it)?.let { raw -> raw.isNotBlank() && raw != "[]" } == true
  }

  private fun install(key: Ed25519KeyPair) {
    secrets.put(SECRET, key.seed)
    val address = Base58.encode(key.publicKey)
    store.putDurable(PUBLIC, address)
    cached = key
    state.value = ClaimKeyState.Ready(address)
  }

  private companion object { const val PUBLIC = "claim.public"; const val SECRET = "claim-key" }
}
