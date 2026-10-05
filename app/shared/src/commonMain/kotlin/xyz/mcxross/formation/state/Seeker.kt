package xyz.mcxross.formation.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Ed25519
import xyz.mcxross.formation.crypto.secureRandomBytes
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.platform.WalletPort
import xyz.mcxross.formation.platform.WalletResult
import xyz.mcxross.formation.session.FormationJson

@Serializable
data class SeekerIdentity(val wallet: String, val sgt: String?, val simulated: Boolean)

fun interface SeekerCheck {
  suspend fun sgtOf(wallet: String): Result<String?>
}

sealed interface SeekerStatus {
  data object NotASeeker : SeekerStatus

  data object NotLinked : SeekerStatus

  data object Checking : SeekerStatus

  data class Verified(val identity: SeekerIdentity) : SeekerStatus

  data class NeedsApproval(val message: String) : SeekerStatus

  data class NoToken(val wallet: String) : SeekerStatus
}

class SeekerState(
  private val store: KeyValueStore,
  private val wallet: WalletPort,
  private val isSeeker: Boolean,
  private val debug: Boolean,
  private val check: SeekerCheck,
) {
  private val _identity = MutableStateFlow(load())
  val identity: StateFlow<SeekerIdentity?> = _identity.asStateFlow()

  private val _status =
    MutableStateFlow(_identity.value?.let { SeekerStatus.Verified(it) } ?: SeekerStatus.NotASeeker)
  val status: StateFlow<SeekerStatus> = _status.asStateFlow()

  private val lock = Mutex()

  // Never opens the wallet: a linked Seeker is re-checked by its stored address, an unlinked one
  // waits for the owner to choose to link from a screen that says why.
  suspend fun autoVerify() = lock.withLock {
    val stored = _identity.value
    when {
      stored?.simulated == true -> Unit
      stored != null -> recheck(stored)
      _status.value is SeekerStatus.NeedsApproval || _status.value is SeekerStatus.NoToken -> Unit
      else -> _status.value = if (isSeeker) SeekerStatus.NotLinked else SeekerStatus.NotASeeker
    }
  }

  suspend fun link() = lock.withLock {
    try {
      connectAndCheck()
    } finally {
      // Leaving onboarding can cancel the wallet request. Do not strand a later visit in Checking.
      if (_status.value == SeekerStatus.Checking) {
        _status.value = _identity.value?.let { SeekerStatus.Verified(it) }
          ?: if (isSeeker) SeekerStatus.NotLinked else SeekerStatus.NotASeeker
      }
    }
  }

  fun pretend(on: Boolean, claimAddress: String) {
    if (!debug) return
    store(if (on) SeekerIdentity(claimAddress, null, simulated = true) else null)
  }

  fun forget() = store(null)

  /** Hosting requires the identity produced by verification, not an onboarding choice. */
  fun requireHostIdentity(): SeekerIdentity = checkNotNull(identity.value) {
    "Link a Seeker to unlock play, or join someone who has one."
  }

  // Solana Mobile's Genesis Token check needs the wallet to sign, not just name an address that holds one.
  private suspend fun connectAndCheck() {
    _status.value = SeekerStatus.Checking
    val message = ("Formation will check this wallet holds your Seeker Genesis Token.\n" +
      "Nonce: ${Base58.encode(secureRandomBytes(16))}").encodeToByteArray()
    _status.value =
      when (val signed = wallet.signIn(message)) {
        WalletResult.NoWallet -> SeekerStatus.NeedsApproval("No Seed Vault or wallet app answered.")
        is WalletResult.Failed -> SeekerStatus.NeedsApproval(signed.message)
        is WalletResult.Ok -> {
          val address = signed.value.address
          val holdsKey = runCatching { Ed25519.verify(signed.value.signature, message, Base58.decode(address)) }.getOrDefault(false)
          if (!holdsKey) SeekerStatus.NeedsApproval("The wallet's signature doesn't match its address.")
          else check
            .sgtOf(address)
            .fold(
              onSuccess = { sgt ->
                if (sgt == null) SeekerStatus.NoToken(address)
                else {
                  store(SeekerIdentity(address, sgt, simulated = false))
                  return
                }
              },
              onFailure = {
                SeekerStatus.NeedsApproval(
                  "Couldn't check the wallet: ${it.message ?: "network error"}"
                )
              },
            )
        }
      }
  }

  // Network trouble keeps the stored identity; only a definite "no token" drops it.
  private suspend fun recheck(stored: SeekerIdentity) {
    check.sgtOf(stored.wallet).onSuccess { sgt ->
      if (sgt == null) {
        store(null)
        _status.value = SeekerStatus.NoToken(stored.wallet)
      } else if (sgt != stored.sgt) {
        store(stored.copy(sgt = sgt))
      }
    }
  }

  private fun store(identity: SeekerIdentity?) {
    store.put(KEY, identity?.let { FormationJson.encodeToString(SeekerIdentity.serializer(), it) })
    _identity.value = identity
    _status.value = identity?.let { SeekerStatus.Verified(it) } ?: SeekerStatus.NotASeeker
  }

  // A pretend Seeker from a debug build never carries over into a release build.
  private fun load(): SeekerIdentity? =
    store
      .get(KEY)
      ?.let {
        runCatching { FormationJson.decodeFromString(SeekerIdentity.serializer(), it) }.getOrNull()
      }
      ?.takeUnless { it.simulated && !debug }

  private companion object {
    const val KEY = "seeker"
  }
}
