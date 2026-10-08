package xyz.mcxross.formation.state.recovery

// The current iOS shell has no secure export provider. Never fall back to an unencrypted key.
actual fun recoveryCipher(): RecoveryCipher =
  object : RecoveryCipher {
    override val available = false

    override fun encrypt(plain: ByteArray, password: String): ByteArray =
      error("Recovery export is unavailable on this platform")

    override fun decrypt(sealed: ByteArray, password: String): ByteArray =
      error("Recovery import is unavailable on this platform")
  }
