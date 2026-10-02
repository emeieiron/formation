package xyz.mcxross.formation.state

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import xyz.mcxross.formation.crypto.Base64
import xyz.mcxross.formation.crypto.Sha256
import xyz.mcxross.formation.crypto.toHex
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.session.FormationJson
import xyz.mcxross.formation.session.Seal

class SimulatedLedger(
  private val store: KeyValueStore,
  fixtures: List<Opportunity> = emptyList(),
) : RewardLedger {
  override val mode = LedgerMode.SIMULATED

  private val _opportunities =
    MutableStateFlow(if (store.get(KEY_OPPORTUNITIES) == null) fixtures.toList()
      else loadList(store, KEY_OPPORTUNITIES, Opportunity.serializer()))
  override val opportunities: StateFlow<List<Opportunity>> = _opportunities.asStateFlow()

  override val problem: StateFlow<String?> = MutableStateFlow(null)

  private val book = TicketBook(store, KEY_TICKETS)
  override val tickets: StateFlow<List<ClaimTicket>> = book.tickets

  override suspend fun refresh(seeker: SeekerIdentity) {
    val now = now()
    val live = _opportunities.value.filter { it.expiresAt > now }
    save(live.sortedBy { it.expiresAt })
  }

  override suspend fun unlock(
    seeker: SeekerIdentity,
    opportunity: Opportunity,
    seal: Seal,
  ): Result<UnlockReceipt> {
    delay(1_400)
    save(_opportunities.value.filterNot { it.id == opportunity.id })
    val receipt = "sim" + Sha256.digest(Base64.decode(seal.message)).toHex().take(40)
    return Result.success(
      UnlockReceipt(receipt, null, seal.roster.filter { it.wallet != null }.map { it.player })
    )
  }

  override fun keep(ticket: ClaimTicket) = book.keep(ticket)
  override fun restore(tickets: List<ClaimTicket>) = book.merge(tickets)

  override suspend fun stillLocked(opportunity: Opportunity): Boolean? =
    _opportunities.value.any { it.id == opportunity.id }

  override suspend fun claim(ticket: ClaimTicket, recipient: String): Result<String> {
    delay(900)
    val receipt =
      "sim" +
        Sha256.digest((ticket.opportunity.value + recipient).encodeToByteArray()).toHex().take(40)
    book.claimed(ticket, ticket.wallet ?: recipient, receipt)
    return Result.success(receipt)
  }

  private fun save(list: List<Opportunity>) {
    _opportunities.value = list
    store.put(
      KEY_OPPORTUNITIES,
      FormationJson.encodeToString(ListSerializer(Opportunity.serializer()), list),
    )
  }

  private companion object {
    const val KEY_OPPORTUNITIES = "sim.opportunities"
    const val KEY_TICKETS = "sim.tickets"
  }
}
