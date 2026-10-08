package xyz.mcxross.formation.state

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Base64
import xyz.mcxross.formation.crypto.secureRandomBytes
import xyz.mcxross.formation.platform.DeviceAttestation
import xyz.mcxross.formation.session.SeekerPolicy
import xyz.mcxross.formation.session.SeekerPresence

sealed interface HardwareCheck {
  data object Unknown : HardwareCheck

  data object Checking : HardwareCheck

  data object Proven : HardwareCheck

  data class Failed(val message: String) : HardwareCheck
}

// Whether this phone's secure hardware attests that it is a Seeker with a locked bootloader running
// this app.
// Hosting never depends on it; the wallet gates hosting. It backs a "Seeker present" badge once
// attestation
// has been confirmed on a real Seeker, and developer builds can run it from Settings.
class SeekerHardware(
  private val attestation: DeviceAttestation,
  private val revocations: AttestationRevocations,
  private val now: () -> Long,
) {
  private val _local = MutableStateFlow<HardwareCheck>(HardwareCheck.Unknown)
  val local: StateFlow<HardwareCheck> = _local.asStateFlow()
  private val lock = Mutex()

  suspend fun proveThisPhone(): Boolean = lock.withLock {
    if (_local.value == HardwareCheck.Proven) return true
    _local.value = HardwareCheck.Checking
    val challenge = SeekerPresence.challenge("check-${Base58.encode(secureRandomBytes(16))}", "")
    val chain =
      try {
        attestation.attest(challenge).map(Base64::encode)
      } catch (e: CancellationException) {
        _local.value = HardwareCheck.Unknown
        throw e
      } catch (e: Exception) {
        _local.value =
          HardwareCheck.Failed(
            e.message?.takeIf { it.isNotBlank() }
              ?: "This phone's secure hardware couldn't attest it."
          )
        return false
      }
    val policy = SeekerPolicy(attestation.packageName, attestation.signers)
    val problem =
      SeekerPresence.problem(chain, challenge, policy, now(), revocations.current() ?: emptySet())
    _local.value = problem?.let(HardwareCheck::Failed) ?: HardwareCheck.Proven
    problem == null
  }
}
