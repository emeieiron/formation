package xyz.mcxross.formation.link

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

fun memoryLink(aName: String = "a", bName: String = "b"): Pair<LinkChannel, LinkChannel> {
  val toA = Channel<String>(Channel.UNLIMITED)
  val toB = Channel<String>(Channel.UNLIMITED)
  return MemoryChannel(toA, toB, bName) to MemoryChannel(toB, toA, aName)
}

private class MemoryChannel(
  private val inbox: Channel<String>,
  private val outbox: Channel<String>,
  override val peer: String,
) : LinkChannel {
  override val incoming: Flow<String> = inbox.receiveAsFlow()

  override suspend fun send(frame: String): Boolean =
    try {
      outbox.send(frame)
      true
    } catch (_: ClosedSendChannelException) {
      false
    }

  override suspend fun close() {
    outbox.close()
    inbox.close()
  }
}
