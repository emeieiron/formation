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

// The vault entry a budget unlocks.
@Serializable
@JvmInline
value class OpportunityId(val value: String) {
  override fun toString() = value
}
