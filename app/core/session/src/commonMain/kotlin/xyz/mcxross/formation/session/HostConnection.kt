package xyz.mcxross.formation.session

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import xyz.mcxross.formation.link.LinkChannel

internal class HostConnection(
  private val channel: LinkChannel,
  val local: Boolean,
  val challenge: AdmissionChallenge,
  private val proof: SeekerProof?,
  private val json: Json,
) {
  // The nonce this phone sent in its hello; the Seeker echoes it in the signed welcome.
  var presence: String = ""

  // Snapshot traffic can discard old frames without holding up the referee.
  val outbox = Channel<String>(512, BufferOverflow.DROP_OLDEST)
  fun send(frame: String) { outbox.trySend(frame) }

  suspend fun run(received: suspend (ToHost) -> Unit, closed: () -> Unit, timedOut: () -> Unit) {
    send(json.encodeToString(ToPlayer.serializer(), ToPlayer.Authenticate(challenge, proof)))
    try {
      coroutineScope {
        val deadline = launch { delay(10_000); timedOut() }
        val writer = launch {
          for (frame in outbox) if (!channel.send(frame)) break
          channel.close()
        }
        try {
          channel.incoming.collect { text ->
            runCatching { json.decodeFromString(ToHost.serializer(), text) }.getOrNull()?.let { received(it) }
          }
        } finally { writer.cancel(); deadline.cancel() }
      }
    } finally { closed(); channel.close() }
  }
}
