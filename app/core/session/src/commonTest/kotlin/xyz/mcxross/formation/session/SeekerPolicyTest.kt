package xyz.mcxross.formation.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.mcxross.formation.crypto.AttestedApplication
import xyz.mcxross.formation.crypto.AttestedKey
import xyz.mcxross.formation.crypto.AuthorizationList
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.crypto.KeyDescription
import xyz.mcxross.formation.crypto.Provisioning
import xyz.mcxross.formation.crypto.PublicKeyInfo
import xyz.mcxross.formation.crypto.RootOfTrust
import xyz.mcxross.formation.crypto.SecurityLevel
import xyz.mcxross.formation.crypto.VerifiedBootState

class SeekerPolicyTest {
  private val policy = SeekerPolicy(PACKAGE, setOf(SIGNER), allowSimulated = false)
  private val challenge = SeekerPresence.challenge("session-1", "key")

  private fun attested(
    challenge: ByteArray = this.challenge,
    brand: String? = "solanamobile",
    manufacturer: String? = "Solana Mobile Inc.",
    model: String? = "Seeker",
    locked: Boolean = true,
    boot: VerifiedBootState = VerifiedBootState.VERIFIED,
    application: AttestedApplication? = AttestedApplication(listOf(PACKAGE), listOf(SIGNER)),
  ) = AttestedKey(
    PublicKeyInfo("1.2.840.10045.2.1", "1.2.840.10045.3.1.7", ByteArray(65), ByteArray(91)),
    KeyDescription(
      400, SecurityLevel.TRUSTED_ENVIRONMENT, 400, SecurityLevel.TRUSTED_ENVIRONMENT, challenge,
      AuthorizationList(application = application),
      AuthorizationList(
        origin = AuthorizationList.ORIGIN_GENERATED,
        rootOfTrust = RootOfTrust(ByteArray(32), locked, boot),
        brand = brand, manufacturer = manufacturer, model = model,
      ),
    ),
    Provisioning.REMOTE,
  )

  @Test
  fun acceptsAGenuineSeekerRunningThisApp() {
    assertNull(SeekerPresence.problem(attested(), challenge, policy))
    // Attested names keep the platform's casing; the comparison doesn't depend on it.
    assertNull(SeekerPresence.problem(attested(brand = "SolanaMobile", model = "SEEKER"), challenge, policy))
  }

  @Test
  fun refusesOtherPhones() {
    assertEquals("The phone is a Google Pixel 9 Pro, not a Seeker.",
      SeekerPresence.problem(attested(brand = "google", manufacturer = "Google", model = "Pixel 9 Pro"), challenge, policy))
    assertEquals("The phone is a Solana Mobile Inc. Saga, not a Seeker.",
      SeekerPresence.problem(attested(model = "Saga"), challenge, policy))
    assertEquals("The phone can't prove its model.",
      SeekerPresence.problem(attested(brand = null, manufacturer = null, model = null), challenge, policy))
  }

  @Test
  fun refusesModifiedSystems() {
    val unlocked = "The phone's bootloader is unlocked or its system software isn't verified."
    assertEquals(unlocked, SeekerPresence.problem(attested(locked = false), challenge, policy))
    assertEquals(unlocked, SeekerPresence.problem(attested(boot = VerifiedBootState.SELF_SIGNED), challenge, policy))
  }

  @Test
  fun refusesProofsMadeForAnotherSessionOrApp() {
    assertEquals("The proof was made for a different Formation.",
      SeekerPresence.problem(attested(), SeekerPresence.challenge("session-2", "key"), policy))
    assertEquals("The proof wasn't made by Formation.",
      SeekerPresence.problem(attested(application = AttestedApplication(listOf("com.example.relay"), listOf(SIGNER))), challenge, policy))
    assertEquals("The phone runs a copy of Formation that isn't signed like this one.",
      SeekerPresence.problem(attested(application = AttestedApplication(listOf(PACKAGE), listOf("00"))), challenge, policy))
    assertEquals("The proof doesn't name the app that made it.",
      SeekerPresence.problem(attested(application = null), challenge, policy))
  }

  @Test
  fun checksTheProofBeforeTheAttestation() {
    val key = Base58.encode(Ed25519KeyPair.generate().publicKey)
    assertEquals("This host didn't prove it's a Seeker.", SeekerPresence.problem(null, "s", policy, 0, emptySet()))
    assertEquals("This host's proof is malformed.", SeekerPresence.problem(SeekerProof("not-a-key"), "s", policy, 0, emptySet()))
    assertEquals("This host is a test Seeker from a developer build.",
      SeekerPresence.problem(SeekerProof(key, simulated = true), "s", policy, 0, emptySet()))
    assertNull(SeekerPresence.problem(SeekerProof(key, simulated = true), "s",
      SeekerPolicy(PACKAGE, emptySet(), allowSimulated = true), 0, null))
    assertEquals("Connect to the internet once so this phone can check Seekers.",
      SeekerPresence.problem(SeekerProof(key, listOf("MAA=")), "s", policy, 0, null))
    assertEquals("The attestation chain is too short",
      SeekerPresence.problem(SeekerProof(key, listOf("MAA=")), "s", policy, 0, emptySet()))
  }

  private companion object {
    const val PACKAGE = "xyz.mcxross.formation"
    const val SIGNER = "5e0a"
  }
}
