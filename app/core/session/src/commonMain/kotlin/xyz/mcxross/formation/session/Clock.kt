package xyz.mcxross.formation.session

import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

fun interface Clock {
  fun now(): Long
}

object MonotonicClock : Clock {
  private val origin = TimeSource.Monotonic.markNow()

  override fun now(): Long = origin.elapsedNow().inWholeMilliseconds
}

// NTP style: of the recent samples, the one with the shortest round trip gives the best offset.
class ClockSync(private val local: Clock) {
  private val samples = ArrayDeque<Pair<Long, Long>>()

  @Volatile private var offset = 0L

  @Volatile
  var rttMs: Long? = null
    private set

  val synced: Boolean
    get() = rttMs != null

  fun hostNow(): Long = local.now() + offset

  fun toLocal(hostTime: Long): Long = hostTime - offset

  fun onPong(sent: Long, host: Long, received: Long = local.now()) {
    val rtt = received - sent
    if (rtt < 0) return
    samples.addLast(rtt to host + rtt / 2 - received)
    while (samples.size > WINDOW) samples.removeFirst()
    val best = samples.minBy { it.first }
    rttMs = best.first
    offset = best.second
  }

  private companion object {
    const val WINDOW = 12
  }
}
