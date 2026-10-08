package xyz.mcxross.formation.state

import xyz.mcxross.formation.challenge.Challenge
import xyz.mcxross.formation.challenge.ChallengeRegistry
import xyz.mcxross.formation.model.Budget
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.mosaic.Mosaic
import xyz.mcxross.formation.overdrive.Overdrive
import xyz.mcxross.formation.ricochet.Ricochet

object ChallengeCatalog {
  private val registry =
    ChallengeRegistry(
      challenges = listOf(Overdrive, Ricochet, Mosaic),
      retiredIds =
        setOf("rally", "circuit", "sync", "formation", "rush").map(::ChallengeId).toSet(),
      retiredCodes = (1..5).toSet(),
    )
  val all: List<Challenge<*, *>>
    get() = registry.all

  val formats
    get() = registry.formats

  operator fun get(id: ChallengeId): Challenge<*, *>? = registry[id]

  fun byCode(code: Int): Challenge<*, *>? = registry.byCode(code)

  fun supports(opportunity: Opportunity): Boolean = registry.supports(opportunity)

  // The group sizes of game [id] that [budget] can pay, smallest first.
  fun sizesFor(id: ChallengeId, budget: Budget): List<Int> =
    get(id)?.info?.groupSizes.orEmpty().filter(budget::fits).sorted()

  fun rewardsFor(id: ChallengeId, budgets: List<Budget>, at: Long): List<Budget> =
    budgets.filter { it.playUntil > at && sizesFor(id, it).isNotEmpty() }.sortedBy { it.playUntil }
}
