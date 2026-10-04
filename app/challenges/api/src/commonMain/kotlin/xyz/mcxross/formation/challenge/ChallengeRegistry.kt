package xyz.mcxross.formation.challenge

import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Opportunity

class ChallengeRegistry(
  challenges: List<Challenge<*, *>>,
  retiredIds: Set<ChallengeId> = emptySet(),
  retiredCodes: Set<Int> = emptySet(),
) {
  val all = challenges.toList()
  private val byId = all.associateBy { it.id }
  private val byCode = all.associateBy { it.info.code }
  val formats = all.associate { it.id.value to it.formatVersion }

  init {
    require(byId.size == all.size) { "Game IDs must be unique" }
    require(byCode.size == all.size) { "Game codes must be unique" }
    all.forEach { challenge ->
      val info = challenge.info
      require(info.id.value.isNotBlank()) { "Game IDs must not be blank" }
      require(info.id !in retiredIds) { "Game ID ${info.id.value} is retired" }
      require(info.code !in retiredCodes) { "Game code ${info.code} is retired" }
      require(info.code in 1..65535) { "Game codes must fit an unsigned 16-bit value" }
      require(challenge.formatVersion > 0) { "Format versions must be positive" }
      require(!info.players.isEmpty() && info.players.first >= Opportunity.MIN_PLAYERS &&
        info.players.last <= Opportunity.MAX_PLAYERS) { "Game player counts must be within 2..32" }
      require(info.groupSizes.isNotEmpty() && info.groupSizes.all { it in info.players }) {
        "Game group sizes must fall within its player counts"
      }
    }
  }

  operator fun get(id: ChallengeId): Challenge<*, *>? = byId[id]
  fun byCode(code: Int): Challenge<*, *>? = byCode[code]
  fun supports(opportunity: Opportunity): Boolean =
    get(opportunity.challenge)?.info?.groupSizes?.contains(opportunity.players) == true
}
