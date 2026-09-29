package xyz.mcxross.formation.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.model.OpportunityId
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.session.FormationJson
import xyz.mcxross.formation.session.Seal

@Serializable
data class PendingUnlock(val opportunity: Opportunity, val seal: Seal, val sealedAt: Long)

// Wins sealed by the whole group but not yet unlocked on chain, kept on the Seeker until they are.
class PendingUnlocks(private val store: KeyValueStore) {
  private val _pending = MutableStateFlow(loadList(store, KEY, PendingUnlock.serializer()))
  val pending: StateFlow<List<PendingUnlock>> = _pending.asStateFlow()

  fun add(win: PendingUnlock) {
    if (_pending.value.none { it.opportunity.id == win.opportunity.id }) save(_pending.value + win)
  }

  fun remove(id: OpportunityId) = save(_pending.value.filterNot { it.opportunity.id == id })

  private fun save(list: List<PendingUnlock>) {
    _pending.value = list
    store.put(KEY, FormationJson.encodeToString(ListSerializer(PendingUnlock.serializer()), list))
  }

  private companion object {
    const val KEY = "pending.unlocks"
  }
}
