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
  private val samples = ArrayDeque<Sample>()

  private data class Sample(val rtt: Long, val offset: Long, val received: Long)

  @Volatile private var offset = 0L
  @Volatile private var lastSample: Long? = null

  @Volatile
  var rttMs: Long? = null
    private set

  val synced: Boolean
    get() =
      lastSample?.let {
        local.now() - it in 0..MAX_AGE_MS && (rttMs ?: Long.MAX_VALUE) <= MAX_RTT_MS
      } == true

  fun hostNow(): Long = local.now() + offset

  fun toLocal(hostTime: Long): Long = hostTime - offset

  fun onPong(sent: Long, host: Long, received: Long = local.now()) {
    val rtt = received - sent
    if (rtt < 0) return
    samples.removeAll { received - it.received > MAX_AGE_MS }
    samples.addLast(Sample(rtt, host + rtt / 2 - received, received))
    lastSample = received
    while (samples.size > WINDOW) samples.removeFirst()
    val best = samples.minBy { it.rtt }
    rttMs = best.rtt
    offset = best.offset
  }

  fun reset() {
    samples.clear()
    offset = 0
    rttMs = null
    lastSample = null
  }

  companion object {
    const val MAX_AGE_MS = 10_000L
    const val MAX_RTT_MS = 500L
    const val WINDOW = 12
  }
}
