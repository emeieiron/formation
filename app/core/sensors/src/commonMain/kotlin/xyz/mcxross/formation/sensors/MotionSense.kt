package xyz.mcxross.formation.sensors

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import xyz.mcxross.formation.sensors.api.SensorQuality
import xyz.mcxross.formation.sensors.api.SensorUpdate

class MotionSense(val sensors: SensorHub, scope: CoroutineScope, simulator: MotionSimulator? = null) {
  private val sharing = SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0)
  private val gravity = sensors.gravity.observe().shareIn(scope, sharing, replay = 1)

  val pose = flow<Pose?> {
    var reader = PoseReader()
    gravity.collect { update ->
      if (update is SensorUpdate.Reading && update.sample.quality != SensorQuality.UNRELIABLE) {
        emit(reader.onGravity(update.sample.value, update.sample.timestampNanos / 1_000_000))
      } else { reader = PoseReader(); emit(null) }
    }
  }.stateIn(scope, sharing, null)

  val tilt = gravity.map { update ->
    if (update is SensorUpdate.Reading && update.sample.quality != SensorQuality.UNRELIABLE)
      Tilt.of(update.sample.value) else null
  }.stateIn(scope, sharing, null)

  val gestures = merge(
    flow {
      var reader = JoltReader()
      sensors.linearAcceleration.observe().collect { update ->
        if (update is SensorUpdate.Reading && update.sample.quality != SensorQuality.UNRELIABLE)
          reader.onAcceleration(update.sample.value, update.sample.timestampNanos / 1_000_000).forEach { emit(it) }
        else reader = JoltReader()
      }
    },
    flow {
      var previous: Boolean? = null
      sensors.proximity.observe().collect { update ->
        if (update is SensorUpdate.Reading) {
          val covered = update.sample.value
          if (previous == false && covered) emit(Gesture.COVER)
          previous = covered
        } else previous = null
      }
    },
    simulator?.gestures ?: emptyFlow(),
  ).shareIn(scope, sharing)
}
