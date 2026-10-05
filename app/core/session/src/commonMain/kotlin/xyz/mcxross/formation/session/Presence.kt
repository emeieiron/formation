package xyz.mcxross.formation.session

import kotlinx.serialization.Serializable
import xyz.mcxross.formation.crypto.AttestationException
import xyz.mcxross.formation.crypto.AttestedKey
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Base64
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.crypto.KeyAttestation
import xyz.mcxross.formation.crypto.Sha256
import xyz.mcxross.formation.crypto.VerifiedBootState

// What a Seeker shows every phone that connects. The hardware attestation's challenge commits to the
// session and to [sessionKey], and the Seeker signs its welcome and every snapshot with that key, so a phone
// that isn't the attested Seeker can neither replay the proof nor relay it into a game it runs itself.
@Serializable
data class SeekerProof(
  val sessionKey: String,
  // Base64 DER certificates, leaf first.
  val chain: List<String> = emptyList(),
  // A debug build's pretend Seeker; only debug builds accept it.
  val simulated: Boolean = false,
)

class HostPresence(val proof: SeekerProof, val key: Ed25519KeyPair)

fun interface HostVerifier {
  // Null when [proof] shows a Seeker holds [proof.sessionKey] for [session]; otherwise why not, in words for the player.
  suspend fun problem(session: String, proof: SeekerProof?): String?
}

// [signers] are lowercase-hex SHA-256 digests of the signing certificates a genuine host may carry.
class SeekerPolicy(val packageName: String, val signers: Set<String>, val allowSimulated: Boolean)

object SeekerPresence {
  const val BRAND = "solanamobile"
  const val MANUFACTURER = "Solana Mobile Inc."
  const val MODEL = "Seeker"

  fun challenge(session: String, sessionKey: String): ByteArray =
    Sha256.digest("formation.seeker.v1\n$session\n$sessionKey".encodeToByteArray())

  fun signed(session: String, message: String): ByteArray = "formation.host.v1\n$session\n$message".encodeToByteArray()

  // [revoked] is Google's revocation list; null when this phone has never fetched it.
  fun problem(proof: SeekerProof?, session: String, policy: SeekerPolicy, now: Long, revoked: Set<String>?): String? {
    if (proof == null) return "This host didn't prove it's a Seeker."
    if (runCatching { Base58.decode(proof.sessionKey).size }.getOrNull() != 32) return "This host's proof is malformed."
    if (proof.simulated) return if (policy.allowSimulated) null else "This host is a test Seeker from a developer build."
    revoked ?: return "Connect to the internet once so this phone can check Seekers."
    val attested = try {
      KeyAttestation.verify(proof.chain.map(Base64::decode), now, revoked)
    } catch (e: AttestationException) {
      return e.message
    } catch (e: IllegalArgumentException) {
      return "This host's proof is malformed."
    }
    return problem(attested, challenge(session, proof.sessionKey), policy)
  }

  fun problem(attested: AttestedKey, challenge: ByteArray, policy: SeekerPolicy): String? {
    val description = attested.description
    val hardware = description.hardwareEnforced
    if (!description.challenge.contentEquals(challenge)) return "The proof was made for a different Formation."
    val boot = hardware.rootOfTrust ?: return "The proof doesn't say how the phone started up."
    if (!boot.deviceLocked || boot.verifiedBootState != VerifiedBootState.VERIFIED)
      return "The phone's bootloader is unlocked or its system software isn't verified."
    if (hardware.model == null) return "The phone can't prove its model."
    if (!hardware.brand.equals(BRAND, ignoreCase = true) || !hardware.manufacturer.equals(MANUFACTURER, ignoreCase = true) ||
      !hardware.model.equals(MODEL, ignoreCase = true)) return "The phone is a ${hardware.manufacturer} ${hardware.model}, not a Seeker."
    val app = description.softwareEnforced.application ?: return "The proof doesn't name the app that made it."
    if (policy.packageName !in app.packages) return "The proof wasn't made by Formation."
    if (policy.signers.isNotEmpty() && app.signers.none { it in policy.signers })
      return "The phone runs a copy of Formation that isn't signed like this one."
    return null
  }
}
