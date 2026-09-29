package xyz.mcxross.formation.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(size: Int): ByteArray {
  val bytes = ByteArray(size)
  if (size == 0) return bytes
  val status = bytes.usePinned {
    SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0))
  }
  check(status == 0) { "SecRandomCopyBytes failed: $status" }
  return bytes
}
