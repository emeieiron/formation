package xyz.mcxross.formation.state

import kotlin.test.*
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.session.DiagnosticCode
import xyz.mcxross.formation.session.DiagnosticEvent
import xyz.mcxross.formation.state.diagnostics.*

class LocalDiagnosticsTest {
  @Test
  fun tracesStayBoundedAcrossRestartAndClearPersistedData() {
    val data = mutableMapOf<String, String>()
    val store =
      object : KeyValueStore {
        override fun get(key: String) = data[key]

        override fun put(key: String, value: String?) {
          if (value == null) data.remove(key) else data[key] = value
        }
      }
    val trace = LocalDiagnostics(store) { 1_000 }
    val session = trace.nextSession()
    val record = trace.sink(TraceSource.CLIENT, session)
    repeat(LocalDiagnostics.LIMIT + 10) {
      record(DiagnosticEvent(DiagnosticCode.CLOCK_RTT, it.toLong()))
    }
    assertEquals(LocalDiagnostics.LIMIT, trace.entries.value.size)
    assertEquals(11L, trace.entries.value.first().sequence)
    val reopened = LocalDiagnostics(store) { 2_000 }
    assertEquals(trace.export(), reopened.export())
    assertTrue(reopened.nextSession() > session)
    reopened.sink(TraceSource.CLIENT)(DiagnosticEvent(DiagnosticCode.CLOCK_RTT, Long.MAX_VALUE))
    assertNull(reopened.entries.value.last().event.value)
    reopened.clear()
    assertTrue(LocalDiagnostics(store) { 3_000 }.entries.value.isEmpty())
  }
}
