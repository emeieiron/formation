package xyz.mcxross.formation.sensors.api

import kotlinx.coroutines.flow.StateFlow

data class SensorDescriptor(val availability: Availability, val continuous: Boolean = true)

sealed interface BackendUpdate {
  data class Reading(
    val values: List<Float>,
    val timestampNanos: Long,
    val quality: SensorQuality,
  ) : BackendUpdate

  data class Failed(val reason: FailureReason) : BackendUpdate
}

fun interface SensorRegistration {
  fun close()
}

interface SensorBackend {
  val catalog: StateFlow<Map<SensorKind, SensorDescriptor>>

  fun refresh() {}

  fun register(
    kind: SensorKind,
    request: SamplingRequest,
    receive: (BackendUpdate) -> Unit,
  ): SensorRegistration
}
