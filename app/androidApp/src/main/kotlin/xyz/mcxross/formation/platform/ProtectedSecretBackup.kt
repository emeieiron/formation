package xyz.mcxross.formation.platform

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Independent local copy; excluded from system/cloud backup and bound to this installation. */
internal class ProtectedSecretBackup(context: Context) {
  private val directory = context.noBackupFilesDir
  private val cipher = DeviceSecretCipher("formation.recovery.v1")

  fun contains(name: String): Boolean {
    val file = file(name).baseFile
    return file.exists() || File(file.path + ".bak").exists() || File(file.path + ".new").exists()
  }

  fun get(name: String): ByteArray? = runCatching {
    file(name).openRead().use { input ->
      val blob = input.readBytesBounded() ?: return@use null
      if (blob.firstOrNull() != VERSION) return@use null
      cipher.open(blob.copyOfRange(1, blob.size), aad(name))
    }
  }
    .getOrNull()

  fun put(name: String, value: ByteArray) {
    require(value.size <= MAX_BYTES - 29) { "Secret exceeds protected storage limit" }
    val blob = byteArrayOf(VERSION) + cipher.seal(value, aad(name))
    val file = file(name)
    val stream = file.startWrite()
    try {
      stream.write(blob)
      file.finishWrite(stream)
    } catch (e: Exception) {
      file.failWrite(stream)
      throw e
    }
  }

  fun remove(name: String) {
    file(name).delete()
    check(!contains(name)) { "Could not remove protected copy" }
  }

  private fun file(name: String): AtomicFile {
    require(name.matches(Regex("[a-z0-9-]{1,64}"))) { "Invalid secret name" }
    return AtomicFile(File(directory, "recovery-$name.bin"))
  }

  private fun aad(name: String) = "formation.device-recovery.v1:$name".toByteArray(Charsets.UTF_8)

  private fun java.io.InputStream.readBytesBounded(): ByteArray? {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (true) {
      val read = read(buffer)
      if (read < 0) break
      if (output.size() + read > MAX_BYTES) return null
      output.write(buffer, 0, read)
    }
    return output.toByteArray()
  }

  private companion object {
    const val VERSION: Byte = 1
    const val MAX_BYTES = 16_384
  }
}
