package xyz.mcxross.formation.state

import xyz.mcxross.formation.challenge.Challenge
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Opportunity

object ChallengeCatalog {
  val all: List<Challenge<*, *>> = emptyList()

  operator fun get(id: ChallengeId): Challenge<*, *>? = all.firstOrNull { it.id == id }

  fun byCode(code: Int): Challenge<*, *>? = all.firstOrNull { it.info.code == code }

  fun supports(opportunity: Opportunity): Boolean =
    get(opportunity.challenge)?.info?.players?.contains(opportunity.players) == true
}
