package xyz.mcxross.formation.session

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import xyz.mcxross.formation.link.HostAddress
import xyz.mcxross.formation.link.HostFinder

data class NearbyFormation(val address: HostAddress, val beacon: Beacon)

class NearbyScanner(
  private val finder: HostFinder,
  private val fetch: suspend (HostAddress) -> String?,
  private val json: Json = FormationJson,
  private val intervalMs: Long = 2_000,
) {
  val status = finder.status

  fun scan(): Flow<List<NearbyFormation>> = channelFlow {
    val candidates = MutableStateFlow<Set<HostAddress>>(emptySet())
    launch { finder.candidates.collect { candidates.value = it } }
    while (true) {
      val found =
        candidates.value
          .map { address -> async { lookup(address) } }
          .awaitAll()
          .filterNotNull()
          // The same Seeker can answer on several addresses; keep one of each session.
          .groupBy { it.beacon.session }
          .map { (_, same) -> same.minBy { it.address.toString() } }
          .sortedWith(
            compareByDescending<NearbyFormation> { it.beacon.open }.thenBy { it.beacon.host }
          )
      send(found)
      delay(intervalMs)
    }
  }
    .distinctUntilChanged()

  suspend fun lookup(address: HostAddress): NearbyFormation? =
    try {
      withTimeoutOrNull(2_000) { fetch(address) }
        ?.let { decode(it) }
        ?.let { NearbyFormation(address, it) }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }

  private fun decode(text: String): Beacon? = runCatching {
    json.decodeFromString(Beacon.serializer(), text)
  }
    .getOrNull()
}
