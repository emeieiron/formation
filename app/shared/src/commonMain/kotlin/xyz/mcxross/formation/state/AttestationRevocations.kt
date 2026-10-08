package xyz.mcxross.formation.state

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import xyz.mcxross.formation.platform.KeyValueStore

// Google's list of revoked attestation certificates, kept on the phone so it can check a Seeker
// offline.
// The list isn't signed, so each phone fetches its own copy rather than taking one from a host.
class AttestationRevocations(
  private val store: KeyValueStore,
  private val now: () -> Long,
  private val fetch: suspend () -> String?,
) {
  constructor(
    store: KeyValueStore,
    http: HttpClient,
    now: () -> Long,
  ) : this(store, now, { download(http) })

  @Serializable private class Saved(val fetchedAt: Long, val serials: List<String>)

  private val lock = Mutex()
  private var saved: Saved? = runCatching {
    store.get(KEY)?.let { Json.decodeFromString(Saved.serializer(), it) }
  }
    .getOrNull()

  // The freshest list this phone has, refreshed once it's a day old; null if it has never been
  // fetched.
  suspend fun current(): Set<String>? = lock.withLock {
    if (saved.let { it == null || now() - it.fetchedAt > MAX_AGE_MS }) refresh()
    saved?.serials?.toSet()
  }

  // Without touching the network, for the Seeker's own check: the phones that join it fetch their
  // own.
  suspend fun cached(): Set<String>? = lock.withLock { saved?.serials?.toSet() }

  private suspend fun refresh() {
    val serials = fetch()?.let(::parse) ?: return
    saved =
      Saved(now(), serials.sorted()).also {
        store.put(KEY, Json.encodeToString(Saved.serializer(), it))
      }
  }

  companion object {
    const val URL = "https://android.googleapis.com/attestation/status"
    private const val KEY = "attestation.revoked"
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1_000L
    private const val FETCH_TIMEOUT_MS = 6_000L

    private suspend fun download(http: HttpClient): String? =
      try {
        withTimeoutOrNull(FETCH_TIMEOUT_MS) {
          http.get(URL).let { if (it.status.isSuccess()) it.bodyAsText() else null }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        null
      }

    // Every entry counts, suspended ones included, as Google's own verifier treats them.
    fun parse(text: String): Set<String>? = runCatching {
      Json.parseToJsonElement(text)
        .jsonObject
        .getValue("entries")
        .jsonObject
        .keys
        .map { it.lowercase() }
        .toSet()
    }
      .getOrNull()
  }
}
