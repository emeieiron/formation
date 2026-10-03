package xyz.mcxross.formation.state

import xyz.mcxross.formation.challenge.Challenge
import xyz.mcxross.formation.challenge.ChallengeRegistry
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.overdrive.Overdrive

object ChallengeCatalog {
  private val registry = ChallengeRegistry(
    challenges = listOf(Overdrive),
    retiredIds = setOf("rally", "circuit", "sync", "formation", "rush").map(::ChallengeId).toSet(),
    retiredCodes = (1..5).toSet(),
  )
  val all: List<Challenge<*, *>> get() = registry.all
  val formats get() = registry.formats

  operator fun get(id: ChallengeId): Challenge<*, *>? = registry[id]

  fun byCode(code: Int): Challenge<*, *>? = registry.byCode(code)

  fun supports(opportunity: Opportunity): Boolean =
    registry.supports(opportunity)
}
