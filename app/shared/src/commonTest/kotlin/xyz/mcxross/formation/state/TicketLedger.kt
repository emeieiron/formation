package xyz.mcxross.formation.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import xyz.mcxross.formation.model.Budget
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.session.Seal

// Keeps tickets where the Solana ledger does, for tests of what's built on them; it never reaches a
// chain.
internal class TicketLedger(store: KeyValueStore) : RewardLedger {
  private val book = TicketBook(store, "sol.tickets")
  override val tickets: StateFlow<List<ClaimTicket>> = book.tickets
  override val budgets: StateFlow<List<Budget>> = MutableStateFlow(emptyList())
  override val draws: StateFlow<List<OpenDraw>> = MutableStateFlow(emptyList())
  override val problem: StateFlow<String?> = MutableStateFlow(null)

  override fun keep(ticket: ClaimTicket) = book.keep(ticket)

  override fun restore(tickets: List<ClaimTicket>) = book.merge(tickets)

  override suspend fun refresh(seeker: SeekerIdentity) {}

  override suspend fun enter(seeker: SeekerIdentity, draw: OpenDraw): Result<String> =
    error("No chain in tests")

  override suspend fun unlock(
    seeker: SeekerIdentity,
    opportunity: Opportunity,
    seal: Seal,
  ): Result<UnlockReceipt> = error("No chain in tests")

  override suspend fun claim(ticket: ClaimTicket, recipient: String): Result<String> =
    error("No chain in tests")

  override suspend fun stillLocked(opportunity: Opportunity): Boolean? = null
}
