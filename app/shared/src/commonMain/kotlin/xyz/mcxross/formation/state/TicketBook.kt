package xyz.mcxross.formation.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.session.FormationJson

class TicketBook(private val store: KeyValueStore, private val key: String) {
  private val lock = xyz.mcxross.formation.platform.RecordLock()
  private val _tickets = MutableStateFlow(loadList(store, key, ClaimTicket.serializer()))
  val tickets: StateFlow<List<ClaimTicket>> = _tickets.asStateFlow()

  // A share kept while waiting for the unlock is replaced by what the unlock says; nothing else
  // changes it.
  fun keep(ticket: ClaimTicket): Unit = lock.locked {
    val existing = _tickets.value.firstOrNull { it.same(ticket) }
    when {
      existing == null -> save(listOf(ticket) + _tickets.value)
      !existing.claimed &&
        !existing.lapsed &&
        ((!existing.unlocked && ticket.unlocked) || ticket.claimed) ->
        update(ticket.copy(claimDeadline = ticket.claimDeadline ?: existing.claimDeadline))
    }
  }

  fun merge(imported: List<ClaimTicket>): Unit = lock.locked {
    var merged = _tickets.value
    imported.forEach { ticket ->
      val previous = merged.firstOrNull { it.same(ticket) }
      require(previous == null || previous.root == ticket.root) {
        "A saved reward has a different commitment"
      }
      if (previous == null) merged = merged + ticket
      else if (
        !previous.claimed &&
          !previous.lapsed &&
          (ticket.claimed || ticket.lapsed || !previous.unlocked)
      )
        merged = merged.map { if (it.same(ticket)) ticket else it }
    }
    save(merged)
  }

  fun update(ticket: ClaimTicket) = lock.locked {
    save(_tickets.value.map { if (it.same(ticket)) ticket else it })
  }

  private fun ClaimTicket.same(other: ClaimTicket) =
    opportunity == other.opportunity && index == other.index

  fun claimed(ticket: ClaimTicket, to: String, receipt: String): Unit = lock.locked {
    save(
      _tickets.value.map {
        if (it.opportunity == ticket.opportunity && it.index == ticket.index)
          it.copy(claimedTo = to, claimReceipt = receipt)
        else it
      }
    )
  }

  private fun save(list: List<ClaimTicket>) {
    store.putDurable(
      key,
      FormationJson.encodeToString(ListSerializer(ClaimTicket.serializer()), list),
    )
    _tickets.value = list
  }
}
