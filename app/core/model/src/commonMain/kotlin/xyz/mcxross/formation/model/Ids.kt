package xyz.mcxross.formation.model

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class PlayerId(val value: String) {
  override fun toString() = value
}

@Serializable
@JvmInline
value class ChallengeId(val value: String) {
  override fun toString() = value
}

// The same 16 bytes seed the on-chain opportunity account.
@Serializable
@JvmInline
value class OpportunityId(val value: String) {
  override fun toString() = value

  fun bytes(): ByteArray = uuidBytes(value)
}

internal fun uuidBytes(uuid: String): ByteArray {
  val hex = uuid.replace("-", "")
  require(hex.length == 32 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
    "Not a UUID: $uuid"
  }
  return ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
