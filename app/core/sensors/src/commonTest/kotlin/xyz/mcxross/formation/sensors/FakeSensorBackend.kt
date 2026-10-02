package xyz.mcxross.formation.sensors

import kotlinx.coroutines.flow.MutableStateFlow
import xyz.mcxross.formation.sensors.api.*

internal class FakeSensorBackend(vararg supported: SensorKind) : SensorBackend {
  override val catalog = MutableStateFlow(SensorKind.entries.associateWith {
    SensorDescriptor(
      if (it in supported) Availability.Available(SensorSource.PLATFORM)
      else Availability.Unavailable(UnavailableReason.MISSING_HARDWARE),
      it != SensorKind.PROXIMITY && it != SensorKind.LIGHT,
    )
  })
  private val listeners = mutableMapOf<SensorKind, MutableSet<(BackendUpdate) -> Unit>>()
  val periods = mutableListOf<Int>()
  var registrations = 0
  var rejectRegistration = false
  private var timestamp = 0L
  fun active(kind: SensorKind) = listeners[kind]?.size ?: 0

  override fun register(kind: SensorKind, request: SamplingRequest, receive: (BackendUpdate) -> Unit): SensorRegistration {
    if (rejectRegistration) throw IllegalStateException("registration rejected")
    registrations++
    periods += request.periodUs
    listeners.getOrPut(kind) { mutableSetOf() }.add(receive)
    return SensorRegistration { listeners[kind]?.remove(receive) }
  }

  fun emit(kind: SensorKind, vararg values: Float, timestampNanos: Long = timestamp + 20_000_000) {
    timestamp = timestampNanos
    listeners[kind]?.toList()?.forEach { it(BackendUpdate.Reading(values.toList(), timestampNanos, SensorQuality.USABLE)) }
  }
}
