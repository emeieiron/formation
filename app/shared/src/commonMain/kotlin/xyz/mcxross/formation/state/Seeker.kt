package xyz.mcxross.formation.state

import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Ed25519
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.platform.SecretStore
import xyz.mcxross.formation.platform.WalletPort
import xyz.mcxross.formation.platform.WalletResult
import xyz.mcxross.formation.session.FormationJson
import xyz.mcxross.formation.session.HostChecks
import xyz.mcxross.formation.session.HostCredentials

// A wallet holding a Seeker Genesis Token, and its signed [authorization] for this phone's host key.
// A developer build's pretend Seeker has neither.
@Serializable
data class SeekerIdentity(
  val wallet: String,
  val sgt: String?,
  val simulated: Boolean,
  val authorization: String = "",
  val signature: String = "",
)

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
  private val secrets: SecretStore,
  private val wallet: WalletPort,
  private val isSeeker: Boolean,
  private val developer: Boolean,
  private val network: String,
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
    if (!developer) return
    store(if (on) SeekerIdentity(claimAddress, null, simulated = true) else null)
  }

  fun forget() = store(null)

  // Hosting needs a linked wallet, not particular hardware: any phone it authorized can host for it.
  fun requireHostIdentity(): SeekerIdentity = checkNotNull(identity.value) {
    "Link a wallet that holds a Seeker Genesis Token to host, or join someone who has one."
  }

  fun credentials(session: String, opportunity: Opportunity): HostCredentials {
    val owner = requireHostIdentity()
    if (owner.simulated) return HostChecks.simulated(opportunity)
    val key = secrets.get(HOST_KEY)?.let(Ed25519KeyPair::fromSeed) ?: error("Link this wallet again to host from this phone.")
    return HostChecks.credentials(session, opportunity, owner.wallet, key, owner.authorization, owner.signature)
  }

  // One wallet approval does both jobs Solana Mobile's Genesis Token check asks for: the signature proves the
  // wallet holds its key, and the signed text authorizes a fresh host key kept on this phone.
  private suspend fun connectAndCheck() {
    _status.value = SeekerStatus.Checking
    val key = Ed25519KeyPair.generate()
    val authorization = HostChecks.authorization(Base58.encode(key.publicKey), network,
      Clock.System.now().toString().substringBefore('T'))
    val message = authorization.encodeToByteArray()
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
                  secrets.put(HOST_KEY, key.seed)
                  store(SeekerIdentity(address, sgt, simulated = false, authorization, Base58.encode(signed.value.signature)))
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
    // Without its key, an old authorization can't sign a session on this phone.
    if (identity == null) runCatching { secrets.remove(HOST_KEY) }
    _identity.value = identity
    _status.value = identity?.let { SeekerStatus.Verified(it) } ?: if (isSeeker) SeekerStatus.NotLinked else SeekerStatus.NotASeeker
  }

  // A pretend Seeker from a developer build never carries over into a release build.
  private fun load(): SeekerIdentity? =
    store
      .get(KEY)
      ?.let {
        runCatching { FormationJson.decodeFromString(SeekerIdentity.serializer(), it) }.getOrNull()
      }
      ?.takeUnless { it.simulated && !developer }

  private companion object {
    const val KEY = "seeker"
    const val HOST_KEY = "host-key"
  }
}
