package xyz.mcxross.formation.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import xyz.mcxross.formation.link.HostAddress
import xyz.mcxross.formation.link.HostFinder
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Skr

class ClockAndNearbyTest {
  @Test
  fun theShortestRoundTripSetsTheOffset() {
    var local = 1_000L
    val sync = ClockSync { local }
    assertFalse(sync.synced)
    // Host clock runs 5 000 ms ahead. A slow sample first, then a fast one.
    sync.onPong(sent = 900, host = 5_990, received = 1_100) // rtt 200, guess 5 990 + 100 - 1 100
    sync.onPong(sent = 1_090, host = 6_095, received = 1_100) // rtt 10
    assertTrue(sync.synced)
    assertEquals(10L, sync.rttMs)
    assertEquals(6_000L, sync.hostNow())
    assertEquals(1_000L, sync.toLocal(6_000))
    local = 2_000
    assertEquals(7_000L, sync.hostNow())
  }

  private fun beacon(session: String, open: Boolean = true) =
    FormationJson.encodeToString(
      Beacon.serializer(),
      Beacon(
        PROTOCOL_VERSION,
        session,
        "K7QX",
        "Aaron",
        ChallengeId("rally"),
        Skr.of(600),
        5,
        2,
        open,
        Skr.of(75),
        "Squad",
      ),
    )

  @Test
  fun nearbyFormationsAreProbedAndDeduplicated() = runTest {
    val a = HostAddress("192.168.1.20", 47000)
    val b = HostAddress("10.0.2.2", 47001)
    val dead = HostAddress("192.168.1.99", 47000)
    val finder =
      object : HostFinder {
        override val candidates = MutableStateFlow(setOf(a, b, dead))
      }
    val answers = mapOf(a to beacon("s1"), b to beacon("s1"), dead to null)
    val scanner = NearbyScanner(finder, fetch = { answers[it] }, intervalMs = 50)
    val found = scanner.scan().first { it.isNotEmpty() }
    assertEquals(1, found.size)
    assertEquals("s1", found.single().beacon.session)
  }
}
