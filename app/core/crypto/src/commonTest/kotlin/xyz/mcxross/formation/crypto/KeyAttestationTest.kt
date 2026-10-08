package xyz.mcxross.formation.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KeyAttestationTest {
  private fun chain(pem: String) = Certificate.parsePem(pem).map { it.encoded }

  private fun rejects(message: String, verify: () -> Unit) {
    val error = assertFailsWith<AttestationException> { verify() }
    assertTrue(
      message in error.message.orEmpty(),
      "Expected \"$message\", got \"${error.message}\"",
    )
  }

  @Test
  fun pinsBothGoogleRoots() {
    val roots = KeyAttestation.googleRoots
    assertEquals(
      listOf("f92009e853b6b045", null),
      roots.map { it.attribute(Certificate.SERIAL_NUMBER) },
    )
    assertEquals("Key Attestation CA1", roots[1].attribute(Certificate.COMMON_NAME))
    assertTrue(roots.all { it.selfIssued && it.signedBy(it.publicKey) })
  }

  @Test
  fun readsARemotelyProvisionedPixel() {
    val attested =
      KeyAttestation.verify(chain(AttestationVectors.PIXEL_9_PRO_RKP), PIXEL_9_PRO_MADE, emptySet())
    val description = attested.description
    val hardware = description.hardwareEnforced
    assertEquals(Provisioning.REMOTE, attested.provisioning)
    assertEquals(SecurityLevel.TRUSTED_ENVIRONMENT, description.attestationSecurityLevel)
    assertEquals(400, description.attestationVersion)
    assertContentEquals(
      Base64.decode("ZDY4OGQ3NjMtNjExOC00Y2E2LTk0YjItZTZjZDllZDdlNGU0"),
      description.challenge,
    )
    assertEquals(
      listOf("google", "caiman", "caiman", "Google", "Pixel 9 Pro"),
      listOf(
        hardware.brand,
        hardware.device,
        hardware.product,
        hardware.manufacturer,
        hardware.model,
      ),
    )
    assertEquals(true, hardware.rootOfTrust?.deviceLocked)
    assertEquals(VerifiedBootState.VERIFIED, hardware.rootOfTrust?.verifiedBootState)
    assertEquals(160000L to 202511L, hardware.osVersion to hardware.osPatchLevel)
    val application = description.softwareEnforced.application
    assertEquals(listOf("com.google.android.attestation"), application?.packages)
    assertEquals(
      listOf(Base64.decode("EDk47kU35Z6O55L2VFBPuDRvxrNG0LvEQV/DOfz8jsE=").toHex()),
      application?.signers,
    )
    assertEquals("1.2.840.10045.2.1", attested.publicKey.algorithm)
  }

  @Test
  fun followsTheNewEcRootAndStrongBox() {
    val attested =
      KeyAttestation.verify(
        chain(AttestationVectors.PIXEL_STRONGBOX_2026),
        1_771_979_841_867,
        emptySet(),
      )
    assertEquals(SecurityLevel.STRONG_BOX, attested.description.attestationSecurityLevel)
    assertEquals(Provisioning.REMOTE, attested.provisioning)
  }

  @Test
  fun acceptsFactoryKeysWhoseIntermediatesExpired() {
    val attested =
      KeyAttestation.verify(chain(AttestationVectors.XPERIA_FACTORY), 1_780_585_145_000, emptySet())
    assertEquals(Provisioning.FACTORY, attested.provisioning)
    val hardware = attested.description.hardwareEnforced
    assertEquals(
      listOf("docomo", "Sony", "SO-52B"),
      listOf(hardware.brand, hardware.manufacturer, hardware.model),
    )
  }

  @Test
  fun reportsAnUnlockedBootloader() {
    val attested =
      KeyAttestation.verify(
        chain(AttestationVectors.PIXEL_8A_UNLOCKED),
        1_727_389_885_676,
        emptySet(),
      )
    val root = attested.description.hardwareEnforced.rootOfTrust
    assertEquals(false, root?.deviceLocked)
    assertEquals(VerifiedBootState.UNVERIFIED, root?.verifiedBootState)
  }

  @Test
  fun rejectsExpiredOrFutureRemoteChains() {
    val chain = chain(AttestationVectors.PIXEL_9_PRO_RKP)
    rejects("expired") { KeyAttestation.verify(chain, 1_767_139_200_000, emptySet()) }
    rejects("isn't valid yet") { KeyAttestation.verify(chain, 1_735_689_600_000, emptySet()) }
  }

  @Test
  fun rejectsRevokedCertificates() {
    val chain = chain(AttestationVectors.PIXEL_9_PRO_RKP)
    val intermediate = Certificate.parse(chain[2]).serial
    assertEquals("ed74866372b0791cf1478b39fad0f755593ad3", intermediate)
    rejects("revoked") { KeyAttestation.verify(chain, PIXEL_9_PRO_MADE, setOf(intermediate)) }
  }

  @Test
  fun trustsOnlyThePinnedRoots() {
    val chain = chain(AttestationVectors.PIXEL_9_PRO_RKP)
    rejects("doesn't lead to Google's root") {
      KeyAttestation.verify(
        chain,
        PIXEL_9_PRO_MADE,
        emptySet(),
        roots = KeyAttestation.googleRoots.drop(1),
      )
    }
    // Without the device's copy of the root, the pinned key still anchors the chain.
    assertEquals(
      Provisioning.REMOTE,
      KeyAttestation.verify(chain.dropLast(1), PIXEL_9_PRO_MADE, emptySet()).provisioning,
    )
  }

  @Test
  fun rejectsTamperedOrReorderedChains() {
    val chain = chain(AttestationVectors.PIXEL_9_PRO_RKP)
    val leaf =
      chain[0].copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte() }
    rejects("bad signature") {
      KeyAttestation.verify(listOf(leaf) + chain.drop(1), PIXEL_9_PRO_MADE, emptySet())
    }
    rejects("out of order") {
      KeyAttestation.verify(
        listOf(chain[0], chain[2], chain[1]) + chain.drop(3),
        PIXEL_9_PRO_MADE,
        emptySet(),
      )
    }
    rejects("too short") {
      KeyAttestation.verify(listOf(chain[0], chain.last()), PIXEL_9_PRO_MADE, emptySet())
    }
    rejects("can't be read") {
      KeyAttestation.verify(
        listOf(chain[0].copyOf(40)) + chain.drop(1),
        PIXEL_9_PRO_MADE,
        emptySet(),
      )
    }
  }

  @Test
  fun rejectsAMalformedRootOfTrust() {
    val leaf = Certificate.parsePem(AttestationVectors.MALFORMED_ROOT_OF_TRUST).first()
    assertFailsWith<DerException> {
      KeyDescription.parse(leaf.extensions.getValue(KeyDescription.OID))
    }
  }

  private companion object {
    const val PIXEL_9_PRO_MADE = 1_758_900_680_964L
  }
}
