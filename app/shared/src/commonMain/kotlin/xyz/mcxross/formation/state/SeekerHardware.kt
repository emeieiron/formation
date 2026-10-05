package xyz.mcxross.formation.state

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Base64
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.crypto.secureRandomBytes
import xyz.mcxross.formation.platform.DeviceAttestation
import xyz.mcxross.formation.session.HostPresence
import xyz.mcxross.formation.session.HostVerifier
import xyz.mcxross.formation.session.SeekerPolicy
import xyz.mcxross.formation.session.SeekerPresence
import xyz.mcxross.formation.session.SeekerProof

sealed interface HardwareCheck {
  data object Unknown : HardwareCheck

  data object Checking : HardwareCheck

  data object Proven : HardwareCheck

  data class Failed(val message: String) : HardwareCheck
}

// A Genesis Token shows who owns a Seeker, not that the phone in hand is one. Hosting and practice also
// need this phone's secure hardware to attest it's a Seeker with a locked bootloader running this app,
// and every joining phone checks that attestation for itself.
class SeekerHardware(
  private val attestation: DeviceAttestation,
  private val revocations: AttestationRevocations,
  private val debug: Boolean,
  private val now: () -> Long,
) {
  private val _local = MutableStateFlow<HardwareCheck>(HardwareCheck.Unknown)
  val local: StateFlow<HardwareCheck> = _local.asStateFlow()
  private val lock = Mutex()

  private fun policy(allowSimulated: Boolean) = SeekerPolicy(attestation.packageName, attestation.signers, allowSimulated)

  val verifier = HostVerifier { session, proof ->
    val revoked = if (proof == null || proof.simulated) emptySet() else revocations.current()
    SeekerPresence.problem(proof, session, policy(allowSimulated = debug), now(), revoked)
  }

  // A proof for one hosted session, bound to a fresh session key. A debug build's pretend Seeker gets a
  // simulated proof that only other debug builds accept.
  suspend fun host(session: String, simulated: Boolean): HostPresence {
    val key = Ed25519KeyPair.generate()
    val sessionKey = Base58.encode(key.publicKey)
    if (simulated) {
      check(debug) { "Only a Seeker can host." }
      return HostPresence(SeekerProof(sessionKey, simulated = true), key)
    }
    val proof = SeekerProof(sessionKey, attest(SeekerPresence.challenge(session, sessionKey)).map(Base64::encode))
    // Joining phones run this same check; running it here explains a refusal on the Seeker itself.
    SeekerPresence.problem(proof, session, policy(allowSimulated = false), now(), revocations.cached() ?: emptySet())
      ?.let { fail(it) }
    _local.value = HardwareCheck.Proven
    return HostPresence(proof, key)
  }

  // Proves this phone once per launch, for play that happens on it alone.
  suspend fun proveThisPhone(): Boolean = lock.withLock {
    if (_local.value == HardwareCheck.Proven) return true
    _local.value = HardwareCheck.Checking
    try {
      host("practice-${Base58.encode(secureRandomBytes(16))}", simulated = false)
      true
    } catch (e: CancellationException) {
      _local.value = HardwareCheck.Unknown
      throw e
    } catch (e: IllegalStateException) {
      if (_local.value == HardwareCheck.Checking) _local.value = HardwareCheck.Failed(e.message ?: "This phone isn't a Seeker.")
      false
    }
  }

  private suspend fun attest(challenge: ByteArray): List<ByteArray> =
    try {
      attestation.attest(challenge)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      fail(e.message?.takeIf { it.isNotBlank() } ?: "This phone's secure hardware couldn't attest it.")
    }

  private fun fail(problem: String): Nothing {
    _local.value = HardwareCheck.Failed(problem)
    throw IllegalStateException("Only a Seeker can host. $problem")
  }
}
