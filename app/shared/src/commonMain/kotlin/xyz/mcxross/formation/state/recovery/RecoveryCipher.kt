package xyz.mcxross.formation.state.recovery

interface RecoveryCipher {
  val available: Boolean
    get() = true

  fun encrypt(plain: ByteArray, password: String): ByteArray

  fun decrypt(sealed: ByteArray, password: String): ByteArray
}

expect fun recoveryCipher(): RecoveryCipher
