package xyz.mcxross.formation.sensors.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import xyz.mcxross.formation.sensors.api.*
import xyz.mcxross.formation.sensors.capabilities.*

sealed interface PreparationState {
  data object Starting : PreparationState

  data object Ready : PreparationState

  data class Blocked(val assessment: Assessment.Blocked) : PreparationState

  data class Interrupted(val acquisition: Acquisition) : PreparationState
}

class SensorPreparation
internal constructor(
  requirements: Collection<SensorRequirement>,
  inputs: Map<InputCapability, SensorChannel<*>>,
  parent: CoroutineScope,
  timeoutMs: Long,
) {
  private val scope = CoroutineScope(parent.coroutineContext + Job(parent.coroutineContext[Job]))
  private val state = MutableStateFlow<PreparationState>(PreparationState.Starting)
  val readiness = state.asStateFlow()

  init {
    require(timeoutMs > 0)
    val required = requirements.distinctBy { it.capability }
    if (required.isEmpty()) state.value = PreparationState.Ready
    else {
      val readings = required.associate {
        it.capability to MutableStateFlow<Acquisition>(Acquisition.Starting)
      }
      required.forEach { requirement ->
        scope.launch {
          inputs.getValue(requirement.capability).observe().collect { update ->
            readings.getValue(requirement.capability).value =
              when (update) {
                is SensorUpdate.Reading ->
                  if (update.sample.quality == SensorQuality.UNRELIABLE)
                    Acquisition.Failed(FailureReason.INVALID_READING)
                  else Acquisition.Active
                is SensorUpdate.State -> update.acquisition
                SensorUpdate.Gap -> Acquisition.Starting
              }
          }
        }
      }
      scope.launch {
        combine(
            required.map { inputs.getValue(it.capability).availability } + readings.values.toList()
          ) { values ->
            val catalog =
              CapabilityCatalog(
                required
                  .mapIndexed { index, r -> r.capability to values[index] as Availability }
                  .toMap()
              )
            val assessment = catalog.assess(required)
            val acquisition = values.drop(required.size).map { it as Acquisition }
            when {
              assessment is Assessment.Blocked -> PreparationState.Blocked(assessment)
              acquisition.all { it == Acquisition.Active } -> PreparationState.Ready
              acquisition.any { it is Acquisition.Failed || it == Acquisition.Suspended } ->
                PreparationState.Interrupted(
                  acquisition.first { it is Acquisition.Failed || it == Acquisition.Suspended }
                )
              else -> PreparationState.Starting
            }
          }
          .collect { state.value = it }
      }
      scope.launch {
        delay(timeoutMs)
        if (state.value == PreparationState.Starting)
          state.value = PreparationState.Interrupted(Acquisition.Failed(FailureReason.NO_READINGS))
      }
    }
  }

  fun close() {
    scope.cancel()
  }
}
