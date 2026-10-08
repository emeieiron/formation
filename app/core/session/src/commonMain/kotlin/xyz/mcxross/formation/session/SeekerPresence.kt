package xyz.mcxross.formation.session

import xyz.mcxross.formation.crypto.AttestationException
import xyz.mcxross.formation.crypto.AttestedKey
import xyz.mcxross.formation.crypto.Base64
import xyz.mcxross.formation.crypto.KeyAttestation
import xyz.mcxross.formation.crypto.Sha256
import xyz.mcxross.formation.crypto.VerifiedBootState

// Hardware proof that a phone is a Seeker. It never gates hosting: the wallet does that (see
// HostProof).
// It is kept for a "Seeker present" badge, once attestation has been confirmed on a real Seeker.
//
// [signers] are lowercase-hex SHA-256 digests of the signing certificates a genuine host may carry.
class SeekerPolicy(val packageName: String, val signers: Set<String>)

object SeekerPresence {
  const val BRAND = "solanamobile"
  const val MANUFACTURER = "Solana Mobile Inc."
  const val MODEL = "Seeker"

  fun challenge(session: String, sessionKey: String): ByteArray =
    Sha256.digest("formation.seeker.v1\n$session\n$sessionKey".encodeToByteArray())

  // [chain] is Base64 DER, leaf first. [revoked] is Google's revocation list; null when this phone
  // has never fetched it.
  fun problem(
    chain: List<String>,
    challenge: ByteArray,
    policy: SeekerPolicy,
    now: Long,
    revoked: Set<String>?,
  ): String? {
    revoked ?: return "Connect to the internet once so this phone can check Seekers."
    val attested =
      try {
        KeyAttestation.verify(chain.map(Base64::decode), now, revoked)
      } catch (e: AttestationException) {
        return e.message
      } catch (e: IllegalArgumentException) {
        return "The proof is malformed."
      }
    return problem(attested, challenge, policy)
  }

  fun problem(attested: AttestedKey, challenge: ByteArray, policy: SeekerPolicy): String? {
    val description = attested.description
    val hardware = description.hardwareEnforced
    if (!description.challenge.contentEquals(challenge))
      return "The proof was made for a different Formation."
    val boot = hardware.rootOfTrust ?: return "The proof doesn't say how the phone started up."
    if (!boot.deviceLocked || boot.verifiedBootState != VerifiedBootState.VERIFIED)
      return "The phone's bootloader is unlocked or its system software isn't verified."
    if (hardware.model == null) return "The phone can't prove its model."
    if (
      !hardware.brand.equals(BRAND, ignoreCase = true) ||
        !hardware.manufacturer.equals(MANUFACTURER, ignoreCase = true) ||
        !hardware.model.equals(MODEL, ignoreCase = true)
    )
      return "The phone is a ${hardware.manufacturer} ${hardware.model}, not a Seeker."
    val app =
      description.softwareEnforced.application
        ?: return "The proof doesn't name the app that made it."
    if (policy.packageName !in app.packages) return "The proof wasn't made by Formation."
    if (policy.signers.isNotEmpty() && app.signers.none { it in policy.signers })
      return "The phone runs a copy of Formation that isn't signed like this one."
    return null
  }
}
