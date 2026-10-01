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
  private val key = "sessions.completed"
  private val state = MutableStateFlow(loadList(store, key, CompletedSession.serializer()))
  val entries = state.asStateFlow()

  fun remember(snapshot: SessionSnapshot, address: String? = null) {
    if (snapshot.stage !is Stage.Won) return
    validatedCompletion(snapshot)
    val previous = state.value.firstOrNull { it.snapshot.formation.session == snapshot.formation.session }
    val record = CompletedSession(snapshot, address ?: previous?.address)
    if (record == previous) return
    save(state.value.filterNot { it.snapshot.formation.session == snapshot.formation.session } + record)
  }

  fun remove(session: String) = save(state.value.filterNot { it.snapshot.formation.session == session })

  private fun save(next: List<CompletedSession>) {
    store.putDurable(key, FormationJson.encodeToString(ListSerializer(CompletedSession.serializer()), next))
    state.value = next
  }
}
