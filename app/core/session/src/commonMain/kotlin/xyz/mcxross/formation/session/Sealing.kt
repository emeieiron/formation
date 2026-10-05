package xyz.mcxross.formation.session

import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Base64
import xyz.mcxross.formation.crypto.Ed25519
import xyz.mcxross.formation.crypto.RosterTree
import xyz.mcxross.formation.crypto.Sha256
import xyz.mcxross.formation.crypto.toHex
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.model.OpportunityId
import xyz.mcxross.formation.model.PlayerId

object Sealing {
  private val DOMAIN = "formation/seal/v2".encodeToByteArray()

  // Helpers are everyone but the Seeker, in join order; that order is their roster index.
  fun seal(
    opportunity: Opportunity,
    session: String,
    players: List<Player>,
    result: RoundResult,
  ): Seal {
    val helpers = players.filterNot { it.seeker }
    val split = opportunity.split(helpers.size)
    val roster = helpers.mapIndexed { i, p -> Share(p.id, p.claimKey, i, split.helper, p.wallet) }
    val tree = RosterTree(roster.map(::entry))
    val message = message(opportunity.id, session, tree.root, roster.size, resultHash(result))
    return Seal(
      roster = roster,
      ownerAmount = split.owner,
      root = tree.root.toHex(),
      message = Base64.encode(message),
      required = players.map { it.id },
    )
  }

  fun message(
    opportunity: OpportunityId,
    session: String,
    root: ByteArray,
    size: Int,
    result: ByteArray,
  ): ByteArray =
    DOMAIN +
      Sha256.digest(opportunity.value.encodeToByteArray()) +
      Sha256.digest(session.encodeToByteArray()) +
      root +
      byteArrayOf(size.toByte()) +
      result

  fun resultOf(seal: Seal): ByteArray =
    Base64.decode(seal.message).let { it.copyOfRange(it.size - 32, it.size) }

  fun resultHash(result: RoundResult): ByteArray =
    Sha256.digest(
      FormationJson.encodeToString(RoundResult.serializer(), result).encodeToByteArray()
    )

  fun verify(seal: Seal, player: Player, signature: String): Boolean = runCatching {
    Ed25519.verify(
      Base58.decode(signature),
      Base64.decode(seal.message),
      Base58.decode(player.claimKey),
    )
  }
    .getOrDefault(false)

  fun proof(seal: Seal, player: PlayerId): List<ByteArray>? {
    val share = seal.roster.firstOrNull { it.player == player } ?: return null
    return RosterTree(seal.roster.map(::entry)).proof(share.index)
  }

  private fun entry(share: Share) =
    RosterTree.Entry(Base58.decode(share.claimKey), share.wallet?.let(Base58::decode))
}
