package xyz.mcxross.formation.link

import io.ktor.http.ContentType
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class KtorLinkServer : LinkServer {
  private var server: EmbeddedServer<*, *>? = null

  override suspend fun start(
    ports: IntRange,
    beacon: () -> String,
    onChannel: suspend (LinkChannel) -> Unit,
  ): Int {
    stop()
    var failure: Exception? = null
    for (port in ports) {
      val candidate = createServer(port, beacon, onChannel)
      try {
        withContext(Dispatchers.IO) { candidate.start(wait = false) }
        server = candidate
        return port
      } catch (e: Exception) {
        // Ktor reports a taken port as a cancellation; only give up if we were cancelled.
        currentCoroutineContext().ensureActive()
        failure = e
        withContext(Dispatchers.IO) { runCatching { candidate.stop(0, 0) } }
      }
    }
    throw IllegalStateException("No free port in $ports", failure)
  }

  override suspend fun stop() {
    val running = server ?: return
    server = null
    withContext(Dispatchers.IO) { running.stop(200, 1_000) }
  }

  // Deliberately not called with a CoroutineScope receiver: Ktor would then parent the server to
  // that scope, and whoever started it would wait for it to finish.
  private fun createServer(
    port: Int,
    beacon: () -> String,
    onChannel: suspend (LinkChannel) -> Unit,
  ): EmbeddedServer<*, *> =
    embeddedServer(CIO, port = port, host = "0.0.0.0") {
      install(WebSockets) {
        pingPeriodMillis = 5_000
        timeoutMillis = 15_000
        maxFrameSize = 512L * 1024
      }
      routing {
        get(LinkDefaults.BEACON_PATH) { call.respondText(beacon(), ContentType.Application.Json) }
        webSocket(LinkDefaults.LINK_PATH) {
          // remoteHost would reverse-resolve the address first, which stalls every join for about 30 seconds
          // on a network without DNS, such as the Seeker's own hotspot.
          val peer = "${call.request.origin.remoteAddress}:${call.request.origin.remotePort}"
          onChannel(WebSocketChannel(this, peer))
        }
      }
    }
}
