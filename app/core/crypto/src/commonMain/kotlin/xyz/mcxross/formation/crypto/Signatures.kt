package xyz.mcxross.formation.crypto

// Verification only. Attestation chains are signed with RSA and ECDSA, which each platform implements natively.
expect object Signatures {
  fun verify(algorithm: String, key: PublicKeyInfo, message: ByteArray, signature: ByteArray): Boolean
}

internal enum class KeyType(val oid: String) { RSA("1.2.840.113549.1.1.1"), EC("1.2.840.10045.2.1") }

internal enum class Digest { SHA256, SHA384, SHA512 }

internal class SignatureScheme(val key: KeyType, val digest: Digest)

internal val EC_CURVES = setOf("1.2.840.10045.3.1.7", "1.3.132.0.34", "1.3.132.0.35")

// Only the schemes key attestation uses; anything else fails verification.
internal fun signatureScheme(algorithm: String, key: PublicKeyInfo): SignatureScheme? {
  val scheme = when (algorithm) {
    "1.2.840.113549.1.1.11" -> SignatureScheme(KeyType.RSA, Digest.SHA256)
    "1.2.840.113549.1.1.12" -> SignatureScheme(KeyType.RSA, Digest.SHA384)
    "1.2.840.113549.1.1.13" -> SignatureScheme(KeyType.RSA, Digest.SHA512)
    "1.2.840.10045.4.3.2" -> SignatureScheme(KeyType.EC, Digest.SHA256)
    "1.2.840.10045.4.3.3" -> SignatureScheme(KeyType.EC, Digest.SHA384)
    "1.2.840.10045.4.3.4" -> SignatureScheme(KeyType.EC, Digest.SHA512)
    else -> return null
  }
  if (key.algorithm != scheme.key.oid) return null
  if (scheme.key == KeyType.EC && key.curve !in EC_CURVES) return null
  return scheme
}
