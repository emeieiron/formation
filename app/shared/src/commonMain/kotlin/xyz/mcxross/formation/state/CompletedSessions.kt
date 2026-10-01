package xyz.mcxross.formation.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.session.FormationJson
import xyz.mcxross.formation.session.SessionSnapshot
import xyz.mcxross.formation.session.Stage
import xyz.mcxross.formation.session.validatedCompletion

@Serializable
data class CompletedSession(val snapshot: SessionSnapshot, val address: String? = null)

class CompletedSessions(private val store: KeyValueStore) {
  private val lock = xyz.mcxross.formation.platform.RecordLock()
  private val key = "sessions.completed"
  private val state = MutableStateFlow(loadList(store, key, CompletedSession.serializer()))
  val entries = state.asStateFlow()

  fun remember(snapshot: SessionSnapshot, address: String? = null): Unit = lock.locked {
    if (snapshot.stage !is Stage.Won) return@locked
    validatedCompletion(snapshot)
    val previous = state.value.firstOrNull { it.snapshot.formation.session == snapshot.formation.session }
    val before = previous?.snapshot?.stage as? Stage.Won
    var won = snapshot.stage as Stage.Won
    if (before != null) {
      require(before.seal.message == won.seal.message) { "The saved win has a different commitment" }
      val signatures = before.seal.signatures + won.seal.signatures
      val previousUnlock = before.unlock as? xyz.mcxross.formation.session.Unlock.Unlocked
      val incomingUnlock = won.unlock as? xyz.mcxross.formation.session.Unlock.Unlocked
      val unlock = when {
        previousUnlock != null && incomingUnlock != null -> incomingUnlock.copy(paid = (previousUnlock.paid + incomingUnlock.paid).distinct(), settled = previousUnlock.settled || incomingUnlock.settled)
        previousUnlock != null -> previousUnlock
        else -> won.unlock
      }
      won = won.copy(seal = won.seal.copy(signatures = signatures,
        signed = won.seal.required.filter { it in signatures }),
        unlock = unlock)
    }
    val record = CompletedSession(snapshot.copy(stage = won), address ?: previous?.address)
    if (record == previous) return@locked
    save(state.value.filterNot { it.snapshot.formation.session == snapshot.formation.session } + record)
  }

  fun remove(session: String) = lock.locked { save(state.value.filterNot { it.snapshot.formation.session == session }) }

  private fun save(next: List<CompletedSession>) {
    store.putDurable(key, FormationJson.encodeToString(ListSerializer(CompletedSession.serializer()), next))
    state.value = next
  }
}
