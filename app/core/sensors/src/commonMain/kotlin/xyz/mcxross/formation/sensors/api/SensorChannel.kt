package xyz.mcxross.formation.sensors.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SensorChannel<T> {
  val availability: StateFlow<Availability>

  fun observe(request: SamplingRequest = SamplingRequest.Game): Flow<SensorUpdate<T>>
}
