package xyz.mcxross.formation.crypto

import org.kotlincrypto.hash.sha2.SHA256
import org.kotlincrypto.hash.sha2.SHA512

object Sha256 {
  fun digest(vararg parts: ByteArray): ByteArray =
    SHA256().run {
      parts.forEach { update(it) }
      digest()
    }
}

object Sha512 {
  fun digest(vararg parts: ByteArray): ByteArray =
    SHA512().run {
      parts.forEach { update(it) }
      digest()
    }
}
