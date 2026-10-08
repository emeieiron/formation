package xyz.mcxross.formation.state

import xyz.mcxross.formation.crypto.toHex
import xyz.mcxross.formation.solana.EntryState
import xyz.mcxross.formation.solana.VaultEntry

internal data class ClaimChainState(
  val unlocked: Boolean,
  val expiresAt: Long,
  val root: String,
  val rosterSize: Int,
  val share: Long,
  val claimed: Boolean,
  val deadline: Long,
) {
  companion object {
    fun from(entry: VaultEntry, index: Int, playUntil: Long?) =
      ClaimChainState(
        entry.state == EntryState.UNLOCKED,
        playUntil ?: (entry.closesAtSeconds * 1_000),
        entry.rosterRoot.toHex(),
        entry.rosterSize,
        entry.guestShare.toLong(),
        entry.hasClaimed(index),
        entry.closesAtSeconds * 1_000,
      )

    // The budget hasn't been unlocked yet; it can be until [playUntil].
    fun pending(playUntil: Long) = ClaimChainState(false, playUntil, "", 0, 0, false, playUntil)
  }
}

internal fun reconcileClaim(
  ticket: ClaimTicket,
  chain: ClaimChainState?,
  now: Long?,
  paidTo: String? = null,
): ClaimTicket {
  if (ticket.claimed) return ticket
  if (chain == null) return ticket.copy(lapsed = true)
  if (!chain.unlocked)
    return ticket.copy(unlocked = false, lapsed = now != null && now > chain.expiresAt)
  require(
    ticket.root == chain.root &&
      ticket.index in 0 until chain.rosterSize &&
      ticket.amount.units == chain.share
  ) {
    "The saved reward differs from the chain commitment"
  }
  return when {
    chain.claimed ->
      ticket.copy(
        unlocked = true,
        claimedTo = ticket.wallet ?: paidTo ?: "another wallet",
        claimDeadline = chain.deadline,
      )
    now != null && now > chain.deadline ->
      ticket.copy(unlocked = true, lapsed = true, claimDeadline = chain.deadline)
    else -> ticket.copy(unlocked = true, lapsed = false, claimDeadline = chain.deadline)
  }
}
