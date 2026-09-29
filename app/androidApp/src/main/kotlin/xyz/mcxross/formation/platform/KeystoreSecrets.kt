package xyz.mcxross.formation.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class KeystoreSecrets(context: Context) : SecretStore {
  private val prefs = context.getSharedPreferences("formation.secrets", Context.MODE_PRIVATE)

  override fun get(name: String): ByteArray? {
    val blob = prefs.getString(name, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
    return runCatching {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_BYTES))
      cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }
      .getOrNull()
  }

  override fun put(name: String, value: ByteArray) {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val sealed = cipher.iv + cipher.doFinal(value)
    prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
  }

  private fun key(): SecretKey {
    val keystore = KeyStore.getInstance(PROVIDER).apply { load(null) }
    (keystore.getKey(ALIAS, null) as? SecretKey)?.let {
      return it
    }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
    generator.init(
      KeyGenParameterSpec.Builder(
          ALIAS,
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
    const val ALIAS = "formation.secrets"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_BYTES = 12
  }
}
