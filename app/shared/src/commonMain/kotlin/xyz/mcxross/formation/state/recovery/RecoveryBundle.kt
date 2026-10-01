package xyz.mcxross.formation.state.recovery

import kotlinx.serialization.Serializable
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Base64
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.crypto.RosterTree
import xyz.mcxross.formation.crypto.hexToBytes
import xyz.mcxross.formation.state.ClaimTicket
import xyz.mcxross.formation.state.LedgerMode

@Serializable
data class RecoveryBundle(
  val version: Int = 1,
  val cluster: String,
  val mode: LedgerMode,
  val seed: String,
  val address: String,
  val tickets: List<ClaimTicket>,
) {
  fun validate(expectedCluster: String, expectedMode: LedgerMode): Ed25519KeyPair {
    require(version == 1 && cluster == expectedCluster && mode == expectedMode) { "Recovery belongs to another network or ledger" }
    val key = Ed25519KeyPair.fromSeed(Base64.decode(seed))
    require(Base58.encode(key.publicKey) == address) { "Recovery identity does not match its key" }
    validateTickets(tickets, key)
    return key
  }
}

internal fun validateTickets(tickets: List<ClaimTicket>, key: Ed25519KeyPair) {
  require(tickets.size <= 500 && tickets.map { it.opportunity to it.index }.distinct().size == tickets.size) { "Invalid reward records" }
  tickets.forEach { ticket ->
    require(ticket.opportunity.bytes().size == 16 && ticket.amount.units > 0 && ticket.host.length <= 128) { "Invalid reward record" }
    val root = ticket.root.hexToBytes()
    require(root.size == 32 && ticket.proof.size <= 6) { "Invalid reward proof" }
    if (ticket.index < 0) require(ticket.index == -1 && ticket.claimed && ticket.proof.isEmpty()) { "A host reward must already be paid" }
    else {
      require(ticket.index in 0 until RosterTree.MAX_SIZE) { "Invalid reward position" }
      val wallet = ticket.wallet?.let(Base58::decode)
      require(wallet == null || wallet.size == 32) { "Invalid bound wallet" }
      val proof = ticket.proof.map { it.hexToBytes().also { bytes -> require(bytes.size == 32) } }
      require(RosterTree.verify(ticket.index, key.publicKey, wallet, proof, root)) { "A reward proof does not belong to this claim key" }
    }
  }
}
