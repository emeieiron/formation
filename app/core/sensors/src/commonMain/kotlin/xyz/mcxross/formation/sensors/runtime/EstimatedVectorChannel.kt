package xyz.mcxross.formation.sensors.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import xyz.mcxross.formation.sensors.Vec3
import xyz.mcxross.formation.sensors.api.*

internal class EstimatedVectorChannel(
  private val acceleration: SensorChannel<Vec3>,
  scope: CoroutineScope,
  private val linear: Boolean,
) : SensorChannel<Vec3> {
  override val availability =
    acceleration.availability
      .map {
        if (it is Availability.Available)
          it.copy(
            source = if (it.source == SensorSource.SIMULATED) it.source else SensorSource.ESTIMATED
          )
        else it
      }
      .stateIn(
        scope,
        SharingStarted.Eagerly,
        acceleration.availability.value.let {
          if (it is Availability.Available)
            it.copy(
              source =
                if (it.source == SensorSource.SIMULATED) it.source else SensorSource.ESTIMATED
            )
          else it
        },
      )

  override fun observe(request: SamplingRequest) = flow {
    var gravity: Vec3? = null
    var previous: Long? = null
    acceleration.observe(request).collect { update ->
      when (update) {
        is SensorUpdate.Reading -> {
          val sample = update.sample
          val a = sample.value
          val dt =
            previous?.let { ((sample.timestampNanos - it) / 1_000_000_000f).coerceAtLeast(0f) }
              ?: 0f
          val alpha = if (dt > 0) dt / (0.08f + dt) else 1f
          val g =
            gravity?.let {
              Vec3(
                it.x + alpha * (a.x - it.x),
                it.y + alpha * (a.y - it.y),
                it.z + alpha * (a.z - it.z),
              )
            } ?: a
          gravity = g
          previous = sample.timestampNanos
          emit(
            SensorUpdate.Reading(
              sample.copy(
                value = if (linear) Vec3(a.x - g.x, a.y - g.y, a.z - g.z) else g,
                source =
                  if (sample.source == SensorSource.SIMULATED) sample.source
                  else SensorSource.ESTIMATED,
              )
            )
          )
        }
        else -> {
          gravity = null
          previous = null
          emit(update)
        }
      }
    }
  }
}
