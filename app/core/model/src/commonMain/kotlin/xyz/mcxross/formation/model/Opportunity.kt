package xyz.mcxross.formation.model

import kotlinx.serialization.Serializable

enum class Difficulty(val label: String) {
  EASY("Easy"),
  NORMAL("Normal"),
  HARD("Hard"),
  EXTREME("Extreme"),
}

enum class Tier(val label: String, val players: IntRange) {
  DUO("Duo", 2..2),
  SQUAD("Squad", 3..5),
  CREW("Crew", 6..10),
  CROWD("Crowd", 11..19),
  LEGENDARY("Legendary", 20..Int.MAX_VALUE);

  companion object {
    fun of(players: Int): Tier = entries.first { players in it.players }
  }
}

enum class OpportunityState {
  LOCKED,
  UNLOCKED,
  EXPIRED,
}

@Serializable
data class Opportunity(
  val id: OpportunityId,
  val challenge: ChallengeId,
  val reward: Skr,
  // Includes the Seeker.
  val players: Int,
  val ownerBps: Int,
  val difficulty: Difficulty,
  val expiresAt: Long,
  val sponsor: String,
  val title: String? = null,
  val state: OpportunityState = OpportunityState.LOCKED,
) {
  init {
    require(players >= MIN_PLAYERS) { "A Formation needs at least $MIN_PLAYERS players" }
  }

  val helpers: Int
    get() = players - 1

  val tier: Tier
    get() = Tier.of(players)

  fun split(helpers: Int = this.helpers): RewardSplit = RewardSplit.of(reward, ownerBps, helpers)

  companion object {
    const val MIN_PLAYERS = 2
    const val MAX_PLAYERS = 32
  }
}
