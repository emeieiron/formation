package xyz.mcxross.formation.crypto

class AttestationException(message: String) : Exception(message)

enum class SecurityLevel {
  SOFTWARE,
  TRUSTED_ENVIRONMENT,
  STRONG_BOX,
}

enum class VerifiedBootState {
  VERIFIED,
  SELF_SIGNED,
  UNVERIFIED,
  FAILED,
}

enum class Provisioning {
  REMOTE,
  FACTORY,
  UNKNOWN,
}

class RootOfTrust(
  val verifiedBootKey: ByteArray,
  val deviceLocked: Boolean,
  val verifiedBootState: VerifiedBootState,
)

// Package names and SHA-256 signing-certificate digests (lowercase hex) of the app that made the
// key.
class AttestedApplication(val packages: List<String>, val signers: List<String>)

class AuthorizationList(
  val origin: Long? = null,
  val rootOfTrust: RootOfTrust? = null,
  val osVersion: Long? = null,
  val osPatchLevel: Long? = null,
  val application: AttestedApplication? = null,
  val brand: String? = null,
  val device: String? = null,
  val product: String? = null,
  val manufacturer: String? = null,
  val model: String? = null,
) {
  companion object {
    const val ORIGIN_GENERATED = 0L

    internal fun parse(value: Der): AuthorizationList {
      val tags = mutableMapOf<Int, Der>()
      for (entry in value.sequence()) {
        if (entry.tagClass != Der.CONTEXT || tags.put(entry.tag, entry.explicit()) != null)
          throw DerException("Malformed or repeated authorization ${entry.tag}")
      }
      fun text(tag: Int) = tags[tag]?.octets()?.decodeToString()
      return AuthorizationList(
        origin = tags[702]?.long(),
        rootOfTrust = tags[704]?.let(::rootOfTrust),
        osVersion = tags[705]?.long(),
        osPatchLevel = tags[706]?.long(),
        application = tags[709]?.let { application(Der.parse(it.octets())) },
        brand = text(710),
        device = text(711),
        product = text(712),
        manufacturer = text(716),
        model = text(717),
      )
    }

    private fun rootOfTrust(value: Der): RootOfTrust {
      val fields = value.sequence()
      if (fields.size !in 3..4) throw DerException("Malformed root of trust")
      val state = fields[2].enumerated()
      return RootOfTrust(
        fields[0].octets(),
        fields[1].boolean(),
        VerifiedBootState.entries.getOrNull(state.toInt()).takeIf { state in 0..3 }
          ?: throw DerException("Unknown boot state $state"),
      )
    }

    // Version codes are skipped: they can exceed a Long and nothing here depends on them.
    private fun application(value: Der): AttestedApplication {
      val fields = value.sequence()
      if (fields.size != 2) throw DerException("Malformed application id")
      return AttestedApplication(
        fields[0].set().map { it.sequence().first().octets().decodeToString() },
        fields[1].set().map { it.octets().toHex() },
      )
    }
  }
}

class KeyDescription(
  val attestationVersion: Long,
  val attestationSecurityLevel: SecurityLevel,
  val keyMintVersion: Long,
  val keyMintSecurityLevel: SecurityLevel,
  val challenge: ByteArray,
  val softwareEnforced: AuthorizationList,
  val hardwareEnforced: AuthorizationList,
) {
  companion object {
    const val OID = "1.3.6.1.4.1.11129.2.1.17"

    fun parse(bytes: ByteArray): KeyDescription = malformedAs("key description") { read(bytes) }

    private fun read(bytes: ByteArray): KeyDescription {
      val fields = Der.parse(bytes).sequence()
      if (fields.size != 8) throw DerException("A key description has eight fields")
      return KeyDescription(
        attestationVersion = fields[0].long(),
        attestationSecurityLevel = level(fields[1]),
        keyMintVersion = fields[2].long(),
        keyMintSecurityLevel = level(fields[3]),
        challenge = fields[4].octets(),
        softwareEnforced = AuthorizationList.parse(fields[6]),
        hardwareEnforced = AuthorizationList.parse(fields[7]),
      )
    }

    private fun level(value: Der): SecurityLevel {
      val level = value.enumerated()
      return SecurityLevel.entries.getOrNull(level.toInt()).takeIf { level in 0..2 }
        ?: throw DerException("Unknown security level $level")
    }
  }
}

class AttestedKey(
  val publicKey: PublicKeyInfo,
  val description: KeyDescription,
  val provisioning: Provisioning,
)

// Verifies an Android key attestation chain the way Google's reference verifier does: trust comes
// from the
// pinned root keys, never from the root the device sends, and only the leaf may carry an
// attestation record.
// Seeker-specific rules live with the caller.
object KeyAttestation {
  private const val MAX_CHAIN = 8

  // Google's hardware attestation roots, as published at
  // developer.android.com/privacy-and-security/security-key-attestation.
  // Earlier certificates for the RSA root reuse its key, so chains that end in them still verify.
  val googleRoots: List<Certificate> by lazy {
    listOf(
        """
    MIIFHDCCAwSgAwIBAgIJAPHBcqaZ6vUdMA0GCSqGSIb3DQEBCwUAMBsxGTAXBgNVBAUTEGY5MjAwOWU4NTNiNmIwNDUwHhcNMjIw
    MzIwMTgwNzQ4WhcNNDIwMzE1MTgwNzQ4WjAbMRkwFwYDVQQFExBmOTIwMDllODUzYjZiMDQ1MIICIjANBgkqhkiG9w0BAQEFAAOC
    Ag8AMIICCgKCAgEAr7bHgiuxpwHsK7Qui8xUFmOr75gvMsd/dTEDDJdSSxtf6An7xyqpRR90PL2abxM1dEqlXnf2tqw1Ne4Xwl5j
    lRfdnJLmN0pTy/4lj4/7tv0Sk3iiKkypnEUtR6WfMgH0QZfKHM1+di+y9TFRtv6y//0rb+T+W8a9nsNL/ggjnar86461qO0rOs2c
    Xjp3kOG1FEJ5MVmFmBGtnrKpa73XpXyTqRxB/M0n1n/W9nGqC4FSYa04T6N5RIZGBN2z2MT5IKGbFlbC8UrW0DxW7AYImQQcHtGl
    /m00QLVWutHQoVJYnFPlXTcHYvASLu+RhhsbDmxMgJJ0mcDpvsC4PjvB+TxywElgS70vE0XmLD+OJtvsBslHZvPBKCOdT0MS+tgS
    OIfga+z1Z1g7+DVagf7quvmag8jfPioyKvxnK/EgsTUVi2ghzq8wm27ud/mIM7AY2qEORR8Go3TVB4HzWQgpZrt3i5MIlCaY504L
    zSRiigHCzAPlHws+W0rB5N+er5/2pJKnfBSDiCiFAVtCLOZ7gLiMm0jhO2B6tUXHI/+MRPjy02i59lINMRRev56GKtcd9qO/0kUJ
    WdZTdA2XoS82ixPvZtXQpUpuL12ab+9EaDK8Z4RHJYYfCT3Q5vNAXaiWQ+8PTWm2QgBR/bkwSWc+NpUFgNPN9PvQi8WEg5UmAGMC
    AwEAAaNjMGEwHQYDVR0OBBYEFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMB8GA1UdIwQYMBaAFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMA8G
    A1UdEwEB/wQFMAMBAf8wDgYDVR0PAQH/BAQDAgIEMA0GCSqGSIb3DQEBCwUAA4ICAQB8cMqTllHc8U+qCrOlg3H7174lmaCsbo/b
    J0C17JEgMLb4kvrqsXZs01U3mB/qABg/1t5Pd5AORHARs1hhqGICW/nKMav574f9rZN4PC2ZlufGXb7sIdJpGiO9ctRhiLuYuly1
    0JccUZGEHpHSYM2GtkgYbZba6lsCPYAAP83cyDV+1aOkTf1RCp/lM0PKvmxYN10RYsK631jrleGdcdkxoSK//mSQbgcWnmAEZrzH
    oF1/0gso1HZgIn0YLzVhLSA/iXCX4QT2h3J5z3znluKG1nv8NQdxei2DIIhASWfu804CA96cQKTTlaae2fweqXjdN1/v2nqOhngN
    yz1361mFmr4XmaKH/ItTwOe72NI9ZcwS1lVaCvsIkTDCEXdm9rCNPAY10iTunIHFXRh+7KPzlHGewCq/8TOohBRn0/NNfh7uRslO
    SZ/xKbN9tMBtw37Z8d2vvnXq/YWdsm1+JLVwn6yYD/yacNJBlwpddla8eaVMjsF6nBnIgQOf9zKSe06nSTqvgwUHosgOECZJZ1Eu
    zbH4yswbt02tKtKEFhx+v+OTge/06V+jGsqTWLsfrOCNLuA8H++z+pUENmpqnnHovaI47gC+TNpkgYGkkBT6B/m/U01BuOBBTzhI
    lMEZq9qkDWuM2cA5kW5V3FJUcfHnw1IdYIg2Wxg7yHcQZemFQg==
      """,
        """
    MIICIjCCAaigAwIBAgIRAISp0Cl7DrWK5/8OgN52BgUwCgYIKoZIzj0EAwMwUjEcMBoGA1UEAwwTS2V5IEF0dGVzdGF0aW9uIENB
    MTEQMA4GA1UECwwHQW5kcm9pZDETMBEGA1UECgwKR29vZ2xlIExMQzELMAkGA1UEBhMCVVMwHhcNMjUwNzE3MjIzMjE4WhcNMzUw
    NzE1MjIzMjE4WjBSMRwwGgYDVQQDDBNLZXkgQXR0ZXN0YXRpb24gQ0ExMRAwDgYDVQQLDAdBbmRyb2lkMRMwEQYDVQQKDApHb29n
    bGUgTExDMQswCQYDVQQGEwJVUzB2MBAGByqGSM49AgEGBSuBBAAiA2IABCPaI3FO3z5bBQo8cuiEas4HjqCtG/mLFfRT0MsIssPB
    EEU5Cfbt6sH5yOAxqEi5QagpU1yX4HwnGb7OtBYpDTB57uH5Eczm34A5FNijV3s0/f0UPl7zbJcTx6xwqMIRq6NCMEAwDwYDVR0T
    AQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMCAQYwHQYDVR0OBBYEFFIyuyz7RkOb3NaBqQ5lZuA0QepAMAoGCCqGSM49BAMDA2gAMGUC
    METfjPO/HwqReR2CS7p0ZWoD/LHs6hDi422opifHEUaYLxwGlT9SLdjkVpz0UUOR5wIxAIoGyxGKRHVTpqpGRFiJtQEOOTp/+s1G
    cxeYuR2zh/80lQyu9vAFCj6E4AXc+osmRg==
      """,
      )
      .map { Certificate.parse(Base64.decode(it.filterNot(Char::isWhitespace))) }
  }

  fun verify(
    chain: List<ByteArray>,
    now: Long,
    revoked: Set<String>,
    roots: List<Certificate> = googleRoots,
  ): AttestedKey {
    if (chain.size < 2) fail("The attestation chain is too short")
    if (chain.size > MAX_CHAIN) fail("The attestation chain is too long")
    val certificates =
      try {
        chain.map(Certificate::parse)
      } catch (e: DerException) {
        fail("A certificate can't be read: ${e.message}")
      }
    val top = certificates.last()
    val pinnedCopy = top.takeIf {
      it.selfIssued &&
        roots.any { root -> root.publicKey.encoded.contentEquals(it.publicKey.encoded) }
    }
    val path = if (pinnedCopy != null) certificates.dropLast(1) else certificates
    if (path.size < 2) fail("The attestation chain is too short")
    val anchor =
      pinnedCopy
        ?: roots.firstOrNull { it.subject.contentEquals(path.last().issuer) }
        ?: fail("The attestation doesn't lead to Google's root")
    var issuerKey =
      roots.first { it.publicKey.encoded.contentEquals(anchor.publicKey.encoded) }.publicKey
    var issuerName = anchor.subject
    for (cert in path.asReversed()) {
      if (!cert.issuer.contentEquals(issuerName)) fail("The attestation chain is out of order")
      if (!cert.signedBy(issuerKey)) fail("A certificate in the attestation has a bad signature")
      issuerKey = cert.publicKey
      issuerName = cert.subject
    }
    val provisioning = provisioning(path.last())
    // The leaf's dates come from the device itself, so they prove nothing.
    for (cert in path.drop(1)) {
      if (now < cert.notBefore)
        fail("The attestation isn't valid yet. Check this phone's date and time.")
      // Factory keys can't be rotated, so their expired intermediates are still accepted, as Google
      // does.
      if (now > cert.notAfter && provisioning != Provisioning.FACTORY)
        fail("The attestation has expired")
    }
    if (certificates.any { it.serial in revoked })
      fail("Google has revoked a certificate in this attestation")
    if (certificates.drop(1).any { KeyDescription.OID in it.extensions })
      fail("Only the leaf may carry an attestation record")
    val leaf = certificates.first()
    val description =
      try {
        KeyDescription.parse(
          leaf.extensions[KeyDescription.OID] ?: fail("The key has no attestation record")
        )
      } catch (e: DerException) {
        fail("The attestation record can't be read: ${e.message}")
      }
    if (
      description.attestationSecurityLevel == SecurityLevel.SOFTWARE ||
        description.keyMintSecurityLevel == SecurityLevel.SOFTWARE
    )
      fail("The key isn't held in secure hardware")
    val chainLevel = chainLevel(path, provisioning)
    val consistent =
      when (description.attestationSecurityLevel) {
        SecurityLevel.STRONG_BOX -> chainLevel == SecurityLevel.STRONG_BOX
        else -> chainLevel == null || chainLevel == SecurityLevel.TRUSTED_ENVIRONMENT
      }
    if (!consistent) fail("The attestation's security level doesn't match its certificates")
    if (description.hardwareEnforced.origin != AuthorizationList.ORIGIN_GENERATED)
      fail("The key wasn't generated in secure hardware")
    return AttestedKey(leaf.publicKey, description, provisioning)
  }

  private fun provisioning(intermediate: Certificate) =
    when {
      intermediate.attribute(Certificate.COMMON_NAME) == "Droid CA2" &&
        intermediate.attribute(Certificate.ORGANIZATION) == "Google LLC" -> Provisioning.REMOTE
      intermediate.attribute(Certificate.SERIAL_NUMBER) != null -> Provisioning.FACTORY
      else -> Provisioning.UNKNOWN
    }

  private fun chainLevel(path: List<Certificate>, provisioning: Provisioning): SecurityLevel? =
    when (provisioning) {
      Provisioning.REMOTE ->
        when (path[1].attribute(Certificate.ORGANIZATION)) {
          "TEE" -> SecurityLevel.TRUSTED_ENVIRONMENT
          "StrongBox" -> SecurityLevel.STRONG_BOX
          else -> SecurityLevel.SOFTWARE
        }
      Provisioning.FACTORY ->
        SecurityLevel.STRONG_BOX.takeIf {
          listOf(path[1], path.last()).any { cert ->
            cert.subjectAttributes.any { it.second.contains("strongbox", ignoreCase = true) }
          }
        }
      Provisioning.UNKNOWN -> null
    }

  private fun fail(message: String): Nothing = throw AttestationException(message)
}
