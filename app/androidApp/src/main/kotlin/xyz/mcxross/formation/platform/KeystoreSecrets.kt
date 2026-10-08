package xyz.mcxross.formation.platform

import android.content.Context
import android.util.Base64

internal class KeystoreSecrets(context: Context) : SecretStore {
  private val prefs = context.getSharedPreferences("formation.secrets", Context.MODE_PRIVATE)
  private val cipher = DeviceSecretCipher("formation.secrets")
  private val backup = ProtectedSecretBackup(context)

  override fun contains(name: String) = prefs.contains(name) || backup.contains(name)

  override fun remove(name: String) {
    check(prefs.edit().remove(name).commit()) { "Could not save secret removal" }
    backup.remove(name)
  }

  override fun get(name: String): ByteArray? = runCatching {
    val blob =
      prefs.getString(name, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        ?: return@runCatching null

    cipher.open(blob)
  }
    .getOrNull()

  override fun put(name: String, value: ByteArray) {
    val sealed = cipher.seal(value)
    check(prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()) {
      "Could not save this phone's claim key"
    }
  }

  override fun recoveryCopy(name: String) = backup.get(name)

  @Synchronized
  override fun protect(name: String, value: ByteArray): Boolean {
    val existing = backup.get(name)
    try {
      if (existing?.contentEquals(value) == true) return true
    } finally {
      existing?.fill(0)
    }
    backup.put(name, value)
    val saved = backup.get(name)
    return try {
      saved?.contentEquals(value) == true
    } finally {
      saved?.fill(0)
    }
  }
}
