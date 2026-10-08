package xyz.mcxross.formation.state.recovery

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

actual fun recoveryCipher(): RecoveryCipher = AndroidRecoveryCipher()

// Fixed, versioned envelope: magic/version, salt, nonce, then AES-GCM ciphertext and tag.
internal class AndroidRecoveryCipher : RecoveryCipher {
  private val header = byteArrayOf(70, 82, 77, 1)

  override fun encrypt(plain: ByteArray, password: String): ByteArray {
    val random = SecureRandom()
    val salt = ByteArray(16).also(random::nextBytes)
    val nonce = ByteArray(12).also(random::nextBytes)
    val prefix = header + salt + nonce
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt), GCMParameterSpec(128, nonce))
    cipher.updateAAD(prefix)
    return prefix + cipher.doFinal(plain)
  }

  override fun decrypt(sealed: ByteArray, password: String): ByteArray {
    require(sealed.size in 48..1_000_000 && sealed.copyOfRange(0, 4).contentEquals(header)) {
      "Unsupported recovery file"
    }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
      Cipher.DECRYPT_MODE,
      derive(password, sealed.copyOfRange(4, 20)),
      GCMParameterSpec(128, sealed.copyOfRange(20, 32)),
    )
    cipher.updateAAD(sealed.copyOfRange(0, 32))
    return cipher.doFinal(sealed, 32, sealed.size - 32)
  }

  private fun derive(password: String, salt: ByteArray): SecretKeySpec {
    val spec = PBEKeySpec(password.toCharArray(), salt, 600_000, 256)
    return try {
      val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
      try {
        SecretKeySpec(bytes, "AES")
      } finally {
        bytes.fill(0)
      }
    } finally {
      spec.clearPassword()
    }
  }
}
