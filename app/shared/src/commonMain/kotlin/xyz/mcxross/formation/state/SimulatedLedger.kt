package xyz.mcxross.formation.state

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import xyz.mcxross.formation.crypto.Base64
import xyz.mcxross.formation.crypto.Sha256
import xyz.mcxross.formation.crypto.toHex
import xyz.mcxross.formation.model.Budget
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.session.FormationJson
import xyz.mcxross.formation.session.Seal

class SimulatedLedger(
  private val store: KeyValueStore,
  fixtures: List<Budget> = emptyList(),
) : RewardLedger {
  override val mode = LedgerMode.SIMULATED

  private val _budgets =
    MutableStateFlow(if (store.get(KEY_BUDGETS) == null) fixtures.toList()
      else loadList(store, KEY_BUDGETS, Budget.serializer()))
  override val budgets: StateFlow<List<Budget>> = _budgets.asStateFlow()

  override val draws: StateFlow<List<OpenDraw>> = MutableStateFlow(emptyList())

  override val problem: StateFlow<String?> = MutableStateFlow(null)

  private val book = TicketBook(store, KEY_TICKETS)
  override val tickets: StateFlow<List<ClaimTicket>> = book.tickets

  override suspend fun refresh(seeker: SeekerIdentity) {
    val now = now()
    save(_budgets.value.filter { it.playUntil > now }.sortedBy { it.playUntil })
  }

  override suspend fun enter(seeker: SeekerIdentity, draw: OpenDraw): Result<String> =
    Result.failure(IllegalStateException("Draws run on Solana"))

  override suspend fun unlock(
    seeker: SeekerIdentity,
    opportunity: Opportunity,
    seal: Seal,
  ): Result<UnlockReceipt> {
    delay(1_400)
    save(_budgets.value.filterNot { it.id == opportunity.id })
    val receipt = "sim" + Sha256.digest(Base64.decode(seal.message)).toHex().take(40)
    return Result.success(
      UnlockReceipt(receipt, null, seal.roster.filter { it.wallet != null }.map { it.player })
    )
  }

  override fun keep(ticket: ClaimTicket) = book.keep(ticket)
  override fun restore(tickets: List<ClaimTicket>) = book.merge(tickets)

  override suspend fun stillLocked(opportunity: Opportunity): Boolean? =
    _budgets.value.any { it.id == opportunity.id }

  override suspend fun claim(ticket: ClaimTicket, recipient: String): Result<String> {
    delay(900)
    val receipt =
      "sim" +
        Sha256.digest((ticket.opportunity.value + recipient).encodeToByteArray()).toHex().take(40)
    book.claimed(ticket, ticket.wallet ?: recipient, receipt)
    return Result.success(receipt)
  }

  private fun save(list: List<Budget>) {
    _budgets.value = list
    store.put(KEY_BUDGETS, FormationJson.encodeToString(ListSerializer(Budget.serializer()), list))
  }

  private companion object {
    const val KEY_BUDGETS = "sim.budgets"
    const val KEY_TICKETS = "sim.tickets"
  }
}
