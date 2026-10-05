package xyz.mcxross.formation.crypto

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

actual object Signatures {
  actual fun verify(algorithm: String, key: PublicKeyInfo, message: ByteArray, signature: ByteArray): Boolean {
    val scheme = signatureScheme(algorithm, key) ?: return false
    val (keyName, signatureName) = when (scheme.key) {
      KeyType.RSA -> "RSA" to "RSA"
      KeyType.EC -> "EC" to "ECDSA"
    }
    return runCatching {
      val publicKey = KeyFactory.getInstance(keyName).generatePublic(X509EncodedKeySpec(key.encoded))
      Signature.getInstance("${scheme.digest.name}with$signatureName").run {
        initVerify(publicKey)
        update(message)
        verify(signature)
      }
    }.getOrDefault(false)
  }
}
