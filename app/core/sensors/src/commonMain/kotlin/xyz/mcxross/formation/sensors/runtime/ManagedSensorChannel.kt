package xyz.mcxross.formation.sensors.runtime

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import xyz.mcxross.formation.sensors.api.*

internal class ManagedSensorChannel<T>(
  private val kind: SensorKind,
  private val backend: SensorBackend,
  private val scope: CoroutineScope,
  private val now: () -> Long,
  private val decode: (List<Float>) -> T,
) : SensorChannel<T> {
  override val availability =
    backend.catalog
      .map { it.getValue(kind).availability }
      .distinctUntilChanged()
      .stateIn(scope, SharingStarted.Eagerly, backend.catalog.value.getValue(kind).availability)

  private sealed interface Command {
    class Attach<T>(val consumer: Channel<SensorUpdate<T>>, val request: SamplingRequest) : Command

    class Detach<T>(val consumer: Channel<SensorUpdate<T>>) : Command

    class Foreground(val active: Boolean) : Command

    data object Refresh : Command

    data object Retry : Command

    data object Tick : Command
  }

  private data class Packet(val generation: Long, val update: BackendUpdate)

  private val commands = Channel<Command>(Channel.UNLIMITED)
  private val readings = Channel<Packet>(64)
  @Volatile private var overflow = false

  init {
    scope.launch {
      val consumers = mutableMapOf<Channel<SensorUpdate<T>>, SamplingRequest>()
      var foreground = false
      var registration: SensorRegistration? = null
      var period: Int? = null
      var generation = 0L
      var lastReceived = now()
      var hasReading = false
      var latest: SensorUpdate.Reading<T>? = null
      var watchdog: Job? = null
      var state: Acquisition = Acquisition.Idle
      fun deliver(consumer: Channel<SensorUpdate<T>>, update: SensorUpdate<T>) {
        val result = consumer.trySend(update)
        if (result.isFailure && !result.isClosed) {
          while (consumer.tryReceive().isSuccess) {}
          consumer.trySend(SensorUpdate.Gap)
          consumer.trySend(update)
        }
      }
      fun broadcast(update: SensorUpdate<T>) {
        consumers.keys.forEach { deliver(it, update) }
      }
      fun transition(next: Acquisition) {
        state = next
        broadcast(SensorUpdate.State(next))
      }
      fun stop() {
        generation++
        registration?.close()
        registration = null
        period = null
        hasReading = false
        latest = null
        watchdog?.cancel()
        watchdog = null
        while (readings.tryReceive().isSuccess) {}
        overflow = false
      }
      fun reconcile(force: Boolean = false) {
        val supported = backend.catalog.value.getValue(kind).availability is Availability.Available
        val nextPeriod = consumers.values.minOfOrNull { it.periodUs }
        if (consumers.isEmpty() || !foreground || !supported) {
          stop()
          transition(
            when {
              consumers.isEmpty() -> Acquisition.Idle
              !foreground -> Acquisition.Suspended
              else -> Acquisition.Failed(FailureReason.UNAVAILABLE)
            }
          )
        } else if (force || nextPeriod != period) {
          stop()
          period = nextPeriod
          lastReceived = now()
          transition(Acquisition.Starting)
          val current = generation
          registration =
            try {
              backend.register(kind, SamplingRequest(nextPeriod!!)) { update ->
                if (!readings.trySend(Packet(current, update)).isSuccess) overflow = true
              }
            } catch (_: Exception) {
              period = null
              transition(Acquisition.Failed(FailureReason.REGISTRATION))
              null
            }
          if (registration != null)
            watchdog = launch {
              while (true) {
                delay(250)
                commands.send(Command.Tick)
              }
            }
        }
      }
      val inventory = launch { backend.catalog.collect { commands.send(Command.Refresh) } }
      try {
        while (true) select<Unit> {
          commands.onReceive { command ->
            when (command) {
              is Command.Attach<*> -> {
                @Suppress("UNCHECKED_CAST")
                val consumer = command.consumer as Channel<SensorUpdate<T>>
                consumers[consumer] = command.request
                deliver(consumer, SensorUpdate.State(state))
                reconcile()
                latest?.let { deliver(consumer, it) }
              }
              is Command.Detach<*> -> {
                consumers.remove(command.consumer)
                reconcile()
              }
              is Command.Foreground -> {
                foreground = command.active
                reconcile()
              }
              Command.Refresh -> reconcile()
              Command.Retry -> reconcile(force = true)
              Command.Tick ->
                if (
                  registration != null &&
                    (!hasReading || backend.catalog.value.getValue(kind).continuous) &&
                    now() - lastReceived > 2_000
                ) {
                  stop()
                  transition(Acquisition.Failed(FailureReason.NO_READINGS))
                }
            }
          }
          readings.onReceive { packet ->
            if (packet.generation == generation) {
              if (overflow) {
                overflow = false
                broadcast(SensorUpdate.Gap)
              }
              when (val update = packet.update) {
                is BackendUpdate.Failed -> {
                  stop()
                  transition(Acquisition.Failed(update.reason))
                }
                is BackendUpdate.Reading -> {
                  val value = runCatching {
                    require(update.values.all { it.isFinite() })
                    decode(update.values)
                  }
                  if (value.isFailure) {
                    stop()
                    transition(Acquisition.Failed(FailureReason.INVALID_READING))
                  } else {
                    lastReceived = now()
                    hasReading = true
                    if (!backend.catalog.value.getValue(kind).continuous) {
                      watchdog?.cancel()
                      watchdog = null
                    }
                    if (state != Acquisition.Active) transition(Acquisition.Active)
                    val source =
                      (backend.catalog.value.getValue(kind).availability as Availability.Available)
                        .source
                    latest =
                      SensorUpdate.Reading(
                        SensorSample(
                          value.getOrThrow(),
                          update.timestampNanos,
                          update.quality,
                          source,
                        )
                      )
                    broadcast(latest!!)
                  }
                }
              }
            }
          }
        }
      } finally {
        stop()
        inventory.cancel()
        consumers.keys.forEach { it.close() }
      }
    }
  }

  fun setForeground(active: Boolean) {
    commands.trySend(Command.Foreground(active))
  }

  fun retry() {
    commands.trySend(Command.Retry)
  }

  override fun observe(request: SamplingRequest) = callbackFlow {
    val updates = Channel<SensorUpdate<T>>(64)
    val forward = launch { for (update in updates) send(update) }
    commands.send(Command.Attach(updates, request))
    awaitClose {
      commands.trySend(Command.Detach(updates))
      updates.close()
      forward.cancel()
    }
  }
    .buffer(0)
}
