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
import xyz.mcxross.formation.session.SessionSnapshot
import xyz.mcxross.formation.session.Stage
import xyz.mcxross.formation.session.Unlock

@Serializable
data class PendingUnlock(val opportunity: Opportunity, val seal: Seal, val sealedAt: Long, val unlocked: Boolean = false)

// Saving a fresh win makes it recoverable, but does not authorize background settlement while the
// active result screen is waiting for the host's Unlock action. Failed or abandoned wins still retry.
internal fun PendingUnlock.canRetryAutomatically(active: SessionSnapshot?): Boolean {
  if (active?.formation?.opportunity?.id != opportunity.id) return true
  val unlock = (active.stage as? Stage.Won)?.unlock
  return unlock != Unlock.Waiting && unlock != Unlock.Unlocking
}

// Wins sealed by the whole group but not yet unlocked on chain, kept on the Seeker until they are.
class PendingUnlocks(private val store: KeyValueStore) {
  private val lock = xyz.mcxross.formation.platform.RecordLock()
  private val _pending = MutableStateFlow(loadList(store, KEY, PendingUnlock.serializer()))
  val pending: StateFlow<List<PendingUnlock>> = _pending.asStateFlow()

  fun add(win: PendingUnlock): Unit = lock.locked {
    if (_pending.value.none { it.opportunity.id == win.opportunity.id }) save(_pending.value + win)
  }

  fun paidPartially(id: OpportunityId) = lock.locked {
    save(_pending.value.map { if (it.opportunity.id == id) it.copy(unlocked = true) else it })
  }

  fun remove(id: OpportunityId) = lock.locked { save(_pending.value.filterNot { it.opportunity.id == id }) }

  private fun save(list: List<PendingUnlock>) {
    store.putDurable(KEY, FormationJson.encodeToString(ListSerializer(PendingUnlock.serializer()), list))
    _pending.value = list
  }

  private companion object {
    const val KEY = "pending.unlocks"
  }
}
