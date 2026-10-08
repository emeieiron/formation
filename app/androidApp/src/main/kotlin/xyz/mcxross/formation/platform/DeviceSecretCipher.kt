package xyz.mcxross.formation.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps wrapping keys non-exportable. Decryption never creates a replacement key. */
internal class DeviceSecretCipher(private val alias: String) {
  fun seal(value: ByteArray, associatedData: ByteArray? = null): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key(create = true) ?: error("Device key unavailable"))
    associatedData?.let(cipher::updateAAD)
    return cipher.iv + cipher.doFinal(value)
  }

  fun open(blob: ByteArray, associatedData: ByteArray? = null): ByteArray? {
    if (blob.size < IV_BYTES + TAG_BYTES) return null
    val key = key(create = false) ?: return null
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, IV_BYTES))
    associatedData?.let(cipher::updateAAD)
    return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
  }

  private fun key(create: Boolean): SecretKey? {
    val keystore = KeyStore.getInstance(PROVIDER).apply { load(null) }
    (keystore.getKey(alias, null) as? SecretKey)?.let {
      return it
    }
    if (!create) return null
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
    generator.init(
      KeyGenParameterSpec.Builder(
          alias,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    )
    return generator.generateKey()
  }

  private companion object {
    const val PROVIDER = "AndroidKeyStore"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_BYTES = 12
    const val TAG_BYTES = 16
  }
}
