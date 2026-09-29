package xyz.mcxross.formation.state

import xyz.mcxross.formation.challenge.Challenge
import xyz.mcxross.formation.challenge.circuit.Circuit
import xyz.mcxross.formation.challenge.formation.FormationChallenge
import xyz.mcxross.formation.challenge.rally.Rally
import xyz.mcxross.formation.challenge.rush.Rush
import xyz.mcxross.formation.challenge.sync.Sync
import xyz.mcxross.formation.model.ChallengeId

object ChallengeCatalog {
  val all: List<Challenge<*, *>> = listOf(Rally, Circuit, Sync, FormationChallenge, Rush)

  operator fun get(id: ChallengeId): Challenge<*, *>? = all.firstOrNull { it.id == id }

  fun byCode(code: Int): Challenge<*, *>? = all.firstOrNull { it.info.code == code }
}
