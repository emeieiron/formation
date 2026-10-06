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
import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.delay
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.platform.SecretStore
import xyz.mcxross.formation.platform.SignedMessage
import xyz.mcxross.formation.platform.WalletPort
import xyz.mcxross.formation.platform.WalletResult
import xyz.mcxross.formation.session.FormationJson
import xyz.mcxross.formation.session.HostChecks
import xyz.mcxross.formation.session.HostCredentials

// A wallet holding a Seeker Genesis Token, and its signed [authorization] for this phone's host key. A [test]
// wallet is this phone's own key, holding a test token on a network where real ones don't exist.
@Serializable
data class SeekerIdentity(
  val wallet: String,
  val sgt: String,
  val authorization: String,
  val signature: String,
  val test: Boolean = false,
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
  private val network: String,
  private val check: SeekerCheck,
  // Null where real Genesis Tokens exist; elsewhere it funds a test wallet with SOL and a test token.
  private val faucet: (suspend (SolanaPublicKey) -> Result<Unit>)? = null,
) {
  val testSeekers: Boolean
    get() = faucet != null

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
      stored != null -> recheck(stored)
      _status.value is SeekerStatus.NeedsApproval || _status.value is SeekerStatus.NoToken -> Unit
      else -> _status.value = if (isSeeker) SeekerStatus.NotLinked else SeekerStatus.NotASeeker
    }
  }

  // A Seeker's own wallet, usually the Seed Vault Wallet, through Mobile Wallet Adapter.
  suspend fun link() = settle { authorize(test = false) { wallet.signIn(it) } }

  // This phone's own test wallet stands in for the Seed Vault Wallet: the faucet gives it fees and a test token,
  // then it links exactly as a real wallet would.
  suspend fun becomeTestSeeker() = settle {
    val fund = faucet ?: return@settle
    _status.value = SeekerStatus.Checking
    val key = testWallet() ?: Ed25519KeyPair.generate().also { secrets.put(TEST_WALLET, it.seed) }
    val address = SolanaPublicKey(key.publicKey)
    fund(address).onFailure {
      _status.value = SeekerStatus.NeedsApproval("Couldn't get a test token: ${it.message ?: "network error"}")
      return@settle
    }
    authorize(test = true) { WalletResult.Ok(SignedMessage(address.base58(), key.sign(it))) }
  }

  // What "host" means on this network: a test token where real ones don't exist, the Seeker's own wallet elsewhere.
  suspend fun becomeHost() = if (testSeekers) becomeTestSeeker() else link()

  fun testWallet(): Ed25519KeyPair? = secrets.get(TEST_WALLET)?.let(Ed25519KeyPair::fromSeed)

  private suspend fun settle(block: suspend () -> Unit) = lock.withLock {
    try {
      block()
    } finally {
      // Leaving onboarding can cancel the wallet request. Do not strand a later visit in Checking.
      if (_status.value == SeekerStatus.Checking) {
        _status.value = _identity.value?.let { SeekerStatus.Verified(it) }
          ?: if (isSeeker) SeekerStatus.NotLinked else SeekerStatus.NotASeeker
      }
    }
  }

  fun forget() = store(null)

  // Hosting needs a linked wallet, not particular hardware: any phone it authorized can host for it.
  fun requireHostIdentity(): SeekerIdentity = checkNotNull(identity.value) {
    "Link a wallet that holds a Seeker Genesis Token to host, or join someone who has one."
  }

  fun credentials(session: String, opportunity: Opportunity): HostCredentials {
    val owner = requireHostIdentity()
    val key = secrets.get(HOST_KEY)?.let(Ed25519KeyPair::fromSeed) ?: error("Link this wallet again to host from this phone.")
    return HostChecks.credentials(session, opportunity, owner.wallet, key, owner.authorization, owner.signature)
  }

  // One wallet approval does both jobs Solana Mobile's Genesis Token check asks for: the signature proves the
  // wallet holds its key, and the signed text authorizes a fresh host key kept on this phone.
  private suspend fun authorize(test: Boolean, signIn: suspend (ByteArray) -> WalletResult<SignedMessage>) {
    _status.value = SeekerStatus.Checking
    val key = Ed25519KeyPair.generate()
    val authorization = HostChecks.authorization(Base58.encode(key.publicKey), network,
      Clock.System.now().toString().substringBefore('T'))
    val message = authorization.encodeToByteArray()
    _status.value =
      when (val signed = signIn(message)) {
        WalletResult.NoWallet -> SeekerStatus.NeedsApproval("No Seed Vault or wallet app answered.")
        is WalletResult.Failed -> SeekerStatus.NeedsApproval(signed.message)
        is WalletResult.Ok -> {
          val address = signed.value.address
          val holdsKey = runCatching { Ed25519.verify(signed.value.signature, message, Base58.decode(address)) }.getOrDefault(false)
          if (!holdsKey) SeekerStatus.NeedsApproval("The wallet's signature doesn't match its address.")
          else sgtOf(address, retries = if (test) 5 else 0)
            .fold(
              onSuccess = { sgt ->
                if (sgt == null) SeekerStatus.NoToken(address)
                else {
                  secrets.put(HOST_KEY, key.seed)
                  store(SeekerIdentity(address, sgt, authorization, Base58.encode(signed.value.signature), test))
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

  // A freshly minted test token can take a moment to show up on the RPC node.
  private suspend fun sgtOf(address: String, retries: Int): Result<String?> {
    repeat(retries) {
      check.sgtOf(address).onSuccess { if (it != null) return Result.success(it) }
      delay(1_500)
    }
    return check.sgtOf(address)
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

  private fun load(): SeekerIdentity? =
    store.get(KEY)?.let { runCatching { FormationJson.decodeFromString(SeekerIdentity.serializer(), it) }.getOrNull() }

  private companion object {
    const val KEY = "seeker"
    const val HOST_KEY = "host-key"
    const val TEST_WALLET = "test-wallet"
  }
}
