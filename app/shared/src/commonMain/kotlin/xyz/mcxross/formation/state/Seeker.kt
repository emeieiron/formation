package xyz.mcxross.formation.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
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

  suspend fun link() = lock.withLock { connectAndCheck() }

  fun pretend(on: Boolean, claimAddress: String) {
    if (!debug) return
    store(if (on) SeekerIdentity(claimAddress, null, simulated = true) else null)
  }

  fun forget() = store(null)

  private suspend fun connectAndCheck() {
    _status.value = SeekerStatus.Checking
    _status.value =
      when (val connected = wallet.connect()) {
        WalletResult.NoWallet -> SeekerStatus.NeedsApproval("No Seed Vault or wallet app answered.")
        is WalletResult.Failed -> SeekerStatus.NeedsApproval(connected.message)
        is WalletResult.Ok -> {
          val address = connected.value.address
          check
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
