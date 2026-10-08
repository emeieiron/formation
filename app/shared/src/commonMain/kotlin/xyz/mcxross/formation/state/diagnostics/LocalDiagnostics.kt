package xyz.mcxross.formation.state.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.platform.RecordLock
import xyz.mcxross.formation.session.DiagnosticEvent
import xyz.mcxross.formation.session.FormationJson

@Serializable
enum class TraceSource {
  HOST,
  CLIENT,
  SETTLEMENT,
  DISCOVERY,
  RECOVERY,
}

@Serializable
data class TraceRecord(
  val sequence: Long,
  val at: Long,
  val session: Long,
  val source: TraceSource,
  val event: DiagnosticEvent,
)

class LocalDiagnostics(private val store: KeyValueStore, private val now: () -> Long) {
  private val lock = RecordLock()
  private val serializer = ListSerializer(TraceRecord.serializer())
  private val state =
    MutableStateFlow(
      store.get(KEY)?.let {
        runCatching { FormationJson.decodeFromString(serializer, it).takeLast(LIMIT) }.getOrNull()
      } ?: emptyList()
    )
  val entries = state.asStateFlow()
  private var sequence = state.value.lastOrNull()?.sequence ?: 0
  private var session = state.value.maxOfOrNull { it.session } ?: 0

  fun nextSession(): Long = lock.locked { ++session }

  fun sink(source: TraceSource, session: Long = 0): (DiagnosticEvent) -> Unit = { event ->
    lock.locked {
      // Latencies, durations and counts only. Reject values outside the supported measurement
      // range.
      val safe = event.copy(value = event.value?.takeIf { it in 0..86_400_000 })
      val record = TraceRecord(++sequence, now(), session, source, safe)
      val next = (state.value + record).takeLast(LIMIT)
      state.value = next
      // Diagnostics must never break a session if storage is unavailable.
      runCatching { store.put(KEY, FormationJson.encodeToString(serializer, next)) }
    }
  }

  fun export(): String = lock.locked { FormationJson.encodeToString(serializer, state.value) }

  fun clear() = lock.locked {
    store.putDurable(KEY, null)
    state.value = emptyList()
  }

  companion object {
    const val LIMIT = 256
    private const val KEY = "diagnostics.local"
  }
}
