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

// One budget a sponsor's contest holds for one Genesis Token: the token's holder unlocks it with
// any game
// whose group fits [maxGuests] guests. [id] is the vault entry it creates, which is unique per
// contest,
// token and [round].
@Serializable
data class Budget(
  val id: OpportunityId,
  val contest: String,
  val sgt: String,
  val amount: Skr,
  val ownerWeight: Int,
  val maxGuests: Int,
  val playUntil: Long,
  val sponsor: String,
  val title: String? = null,
  val round: Int = 0,
  // Won in a draw, so the entry already exists and unlocks with the drawn instruction.
  val drawn: Boolean = false,
) {
  fun fits(players: Int): Boolean = players - 1 in 1..maxGuests

  fun split(guests: Int): RewardSplit = RewardSplit.of(amount, ownerWeight, guests)
}

// A budget as one Formation plays it: the game and group size the host chose.
@Serializable
data class Opportunity(
  val budget: Budget,
  val challenge: ChallengeId,
  // Includes the Seeker.
  val players: Int,
  val difficulty: Difficulty = Difficulty.NORMAL,
) {
  init {
    require(players >= MIN_PLAYERS) { "A Formation needs at least $MIN_PLAYERS players" }
    require(budget.fits(players)) { "This budget can't pay ${players - 1} guests" }
  }

  val id: OpportunityId
    get() = budget.id

  val reward: Skr
    get() = budget.amount

  val expiresAt: Long
    get() = budget.playUntil

  val sponsor: String
    get() = budget.sponsor

  val title: String?
    get() = budget.title

  val helpers: Int
    get() = players - 1

  val tier: Tier
    get() = Tier.of(players)

  fun split(helpers: Int = this.helpers): RewardSplit = budget.split(helpers)

  companion object {
    const val MIN_PLAYERS = 2
    const val MAX_PLAYERS = 32
  }
}
