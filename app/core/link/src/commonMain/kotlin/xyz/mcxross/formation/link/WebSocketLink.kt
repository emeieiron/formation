package xyz.mcxross.formation.link

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class WebSocketChannel(private val session: WebSocketSession, override val peer: String) :
  LinkChannel {
  // Ktor fails incoming with the socket's error (e.g. EOF) when the peer vanishes; that is still a close.
  override val incoming: Flow<String> = flow {
    while (true) {
      val frame = session.incoming.receiveCatching().getOrNull() ?: break
      if (frame is Frame.Text) emit(frame.readText())
    }
  }

  override suspend fun send(frame: String): Boolean =
    try {
      session.send(Frame.Text(frame))
      true
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      false
    }

  override suspend fun close() {
    try {
      session.close()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {}
  }
}

// The client needs the WebSockets plugin installed.
class WebSocketConnector(private val client: HttpClient, private val timeoutMs: Long = 6_000) :
  LinkConnector {
  override suspend fun connect(address: HostAddress): LinkChannel {
    val session =
      withTimeout(timeoutMs) {
        client.webSocketSession(
          host = address.host,
          port = address.port,
          path = LinkDefaults.LINK_PATH,
        )
      }
    return WebSocketChannel(session, address.toString())
  }
}

class BeaconProbe(private val client: HttpClient, private val timeoutMs: Long = 1_500) {
  suspend fun fetch(address: HostAddress): String? =
    withTimeoutOrNull(timeoutMs) {
      try {
        val response = client.get("http://$address${LinkDefaults.BEACON_PATH}")
        if (response.status.isSuccess()) response.bodyAsText() else null
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        null
      }
    }
}
