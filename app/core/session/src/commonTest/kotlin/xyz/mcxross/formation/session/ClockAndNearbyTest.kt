package xyz.mcxross.formation.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.mcxross.formation.link.HostAddress
import xyz.mcxross.formation.link.HostFinder
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Skr

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClockAndNearbyTest {
  @Test
  fun theShortestRoundTripSetsTheOffset() {
    var local = 1_000L
    val sync = ClockSync { local }
    assertFalse(sync.synced)
    // Host clock runs 5 000 ms ahead. A slow sample first, then a fast one.
    sync.onPong(sent = 900, host = 5_990, received = 1_100) // rtt 200, guess 5 990 + 100 - 1 100
    sync.onPong(sent = 1_090, host = 6_095, received = 1_100) // rtt 10
    local = 1_100
    assertTrue(sync.synced)
    assertEquals(10L, sync.rttMs)
    assertEquals(6_100L, sync.hostNow())
    assertEquals(1_000L, sync.toLocal(6_000))
    local = 2_000
    assertEquals(7_000L, sync.hostNow())
  }

  @Test
  fun synchronizationExpiresAndRejectsHighLatencySamples() {
    var local = 1_000L
    val sync = ClockSync { local }
    sync.onPong(990, 995)
    assertTrue(sync.synced)
    local += ClockSync.MAX_AGE_MS + 1
    assertFalse(sync.synced)
    sync.onPong(local - 600, local - 300)
    assertFalse(sync.synced)
    sync.onPong(local - 10, local - 5)
    assertTrue(sync.synced)
    sync.reset()
    assertFalse(sync.synced)
  }

  private fun beacon(session: String, open: Boolean = true) =
    FormationJson.encodeToString(
      Beacon.serializer(),
      Beacon(
        PROTOCOL_VERSION,
        session,
        "K7QX",
        "Theo",
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
  fun anUnresponsiveBeaconHasABoundedLookup() = runTest {
    val scanner =
      NearbyScanner(
        object : HostFinder {
          override val candidates = MutableStateFlow(emptySet<HostAddress>())
        },
        fetch = { awaitCancellation() },
      )
    assertNull(scanner.lookup(HostAddress("192.168.1.99", 47000)))
    assertEquals(2_000L, testScheduler.currentTime)
  }

  @Test
  fun aTransientProbeFailureRetainsTheBeaconUntilItsCacheExpires() = runTest {
    val address = HostAddress("10.0.2.2", 47000)
    val finder =
      object : HostFinder {
        override val candidates = MutableStateFlow(setOf(address))
      }
    var response: String? = beacon("cached-session")
    val scanner =
      NearbyScanner(
        finder,
        fetch = { response },
        intervalMs = 50,
        clock = Clock { testScheduler.currentTime },
      )
    var latest = emptyList<NearbyFormation>()
    backgroundScope.launch { scanner.scan().collect { latest = it } }
    advanceTimeBy(100)
    runCurrent()
    assertEquals("cached-session", latest.single().beacon.session)
    response = null
    advanceTimeBy(5_000)
    runCurrent()
    assertEquals("cached-session", latest.single().beacon.session)
    advanceTimeBy(5_100)
    runCurrent()
    assertTrue(latest.isEmpty())
    response = beacon("recovered-session")
    advanceTimeBy(50)
    runCurrent()
    assertEquals("recovered-session", latest.single().beacon.session)
  }

  @Test
  fun anAddressRemovedFromDiscoveryDoesNotKeepACachedFormation() = runTest {
    val address = HostAddress("10.0.2.2", 47000)
    val finder =
      object : HostFinder {
        override val candidates = MutableStateFlow(setOf(address))
      }
    val scanner =
      NearbyScanner(
        finder,
        fetch = { beacon("cached-session") },
        intervalMs = 50,
        clock = Clock { testScheduler.currentTime },
      )
    var latest = emptyList<NearbyFormation>()
    backgroundScope.launch { scanner.scan().collect { latest = it } }
    advanceTimeBy(100)
    runCurrent()
    assertEquals(1, latest.size)
    finder.candidates.value = emptySet()
    advanceTimeBy(50)
    runCurrent()
    assertTrue(latest.isEmpty())
  }

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
