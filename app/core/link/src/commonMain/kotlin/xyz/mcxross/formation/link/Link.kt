package xyz.mcxross.formation.link

import kotlinx.coroutines.flow.Flow

interface LinkChannel {
  // Collect once; completes when the link closes.
  val incoming: Flow<String>

  suspend fun send(frame: String): Boolean

  suspend fun close()

  val peer: String
}

data class HostAddress(val host: String, val port: Int) {
  override fun toString() = "$host:$port"

  companion object {
    fun parse(text: String): HostAddress? {
      val at = text.lastIndexOf(':')
      if (at <= 0) return null
      val port = text.substring(at + 1).toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
      return HostAddress(text.substring(0, at).trim('[', ']'), port)
    }
  }
}

fun interface LinkConnector {
  suspend fun connect(address: HostAddress): LinkChannel
}

interface LinkServer {
  suspend fun start(
    ports: IntRange,
    beacon: () -> String,
    onChannel: suspend (LinkChannel) -> Unit,
  ): Int

  suspend fun stop()
}

interface Advertiser {
  fun advertise(name: String, port: Int)

  fun stop()
}

interface HostFinder {
  val candidates: Flow<Set<HostAddress>>
}

object LinkDefaults {
  const val PORT = 47_000
  val PORTS = PORT until PORT + 10
  const val SERVICE_TYPE = "_formation._tcp."
  const val LINK_PATH = "/link"
  const val BEACON_PATH = "/beacon"

  // The Android emulator's alias for its host machine.
  const val EMULATOR_HOST = "10.0.2.2"
}
