package xyz.mcxross.formation.link

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class KtorLinkTest {
  private val server = KtorLinkServer()
  private val client = linkHttpClient()

  @AfterTest
  fun tearDown() = runBlocking {
    server.stop()
    client.close()
  }

  @Test
  fun echoesOverAWebSocketAndServesTheBeacon() = runBlocking {
    withTimeout(20_000) {
      val served = CompletableDeferred<String>()
      val port =
        // Clear of the emulator bridge, whose adb forwards hold 127.0.0.1 on the default ports.
        server.start(47_100..47_109, beacon = { """{"code":"K7QX"}""" }) { channel ->
          channel.incoming.collect { frame ->
            if (frame == "bye") channel.close() else channel.send("echo:$frame")
          }
          served.complete(channel.peer)
        }
      val address = HostAddress("127.0.0.1", port)

      assertEquals("""{"code":"K7QX"}""", BeaconProbe(client).fetch(address))

      val link = WebSocketConnector(client).connect(address)
      link.send("one")
      link.send("two")
      assertEquals(listOf("echo:one", "echo:two"), link.incoming.take(2).toList())
      link.send("bye")
      assertTrue(served.await().isNotBlank())
    }
  }

  @Test
  fun incomingEndsWhenTheServerVanishesWithoutClosing() = runBlocking {
    withTimeout(20_000) {
      ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
        launch(Dispatchers.IO) { listener.accept().use { hangUpAfterHello(it) } }
        val link = WebSocketConnector(client).connect(HostAddress("127.0.0.1", listener.localPort))
        assertEquals(listOf("hello"), link.incoming.toList())
      }
    }
  }

  private fun hangUpAfterHello(socket: Socket) {
    val request = socket.getInputStream().bufferedReader().lineSequence().takeWhile { it.isNotEmpty() }.toList()
    val key = request.first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
    val accept = Base64.getEncoder().encodeToString(
      MessageDigest.getInstance("SHA-1").digest("${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".toByteArray()))
    val upgrade = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
      "Sec-WebSocket-Accept: $accept\r\n\r\n"
    // 0x81 opens a final text frame; 5 is its unmasked payload length.
    socket.getOutputStream().write(upgrade.toByteArray() + byteArrayOf(0x81.toByte(), 5) + "hello".toByteArray())
  }

  @Test
  fun skipsPortsThatAreTaken() = runBlocking {
    val other = KtorLinkServer()
    try {
      val first = other.start(LinkDefaults.PORTS, { "{}" }) {}
      val second = server.start(LinkDefaults.PORTS, { "{}" }) {}
      assertTrue(second != first)
    } finally {
      other.stop()
    }
  }

  @Test
  fun missingHostsProbeAsNull() = runBlocking {
    assertEquals(null, BeaconProbe(client, timeoutMs = 800).fetch(HostAddress("127.0.0.1", 1)))
  }

  @Test
  fun memoryLinksCarryFramesBothWays() = runBlocking {
    val (a, b) = memoryLink()
    a.send("ping")
    assertEquals("ping", b.incoming.first())
    b.send("pong")
    assertEquals("pong", a.incoming.first())
    a.close()
    assertEquals(false, b.send("late"))
  }

  @Test
  fun parsesAddresses() {
    assertEquals(HostAddress("10.0.2.2", 47001), HostAddress.parse("10.0.2.2:47001"))
    assertEquals(null, HostAddress.parse("nope"))
    assertEquals(null, HostAddress.parse("host:99999"))
  }
}
