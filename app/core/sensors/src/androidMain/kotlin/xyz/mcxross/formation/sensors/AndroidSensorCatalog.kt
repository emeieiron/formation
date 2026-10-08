package xyz.mcxross.formation.sensors

import android.hardware.Sensor
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.mcxross.formation.sensors.api.Availability
import xyz.mcxross.formation.sensors.api.RecoveryAction
import xyz.mcxross.formation.sensors.api.SensorDescriptor
import xyz.mcxross.formation.sensors.api.SensorKind
import xyz.mcxross.formation.sensors.api.SensorSource
import xyz.mcxross.formation.sensors.api.UnavailableReason

internal class AndroidSensorCatalog(manager: SensorManager?) {
  val sensors =
    SensorKind.entries.associateWith { kind ->
      runCatching { manager?.getDefaultSensor(kind.androidType) }.getOrNull()
    }
  private val state =
    MutableStateFlow(
      sensors.mapValues { (kind, sensor) ->
        SensorDescriptor(
          if (sensor != null) Availability.Available(SensorSource.PLATFORM)
          else
            Availability.Unavailable(
              if (manager == null) UnavailableReason.UNSUPPORTED_PLATFORM
              else UnavailableReason.MISSING_HARDWARE
            ),
          continuous = kind != SensorKind.PROXIMITY && kind != SensorKind.LIGHT,
        )
      }
    )
  val catalog = state.asStateFlow()

  fun restricted(kind: SensorKind) {
    state.value =
      state.value +
        (kind to
          state.value
            .getValue(kind)
            .copy(
              availability =
                Availability.Unavailable(
                  UnavailableReason.SYSTEM_RESTRICTION,
                  RecoveryAction.OPEN_SETTINGS,
                )
            ))
  }

  fun refresh() {
    state.value =
      state.value.mapValues { (kind, descriptor) ->
        if (sensors[kind] != null)
          descriptor.copy(availability = Availability.Available(SensorSource.PLATFORM))
        else descriptor
      }
  }
}

private val SensorKind.androidType: Int
  get() =
    when (this) {
      SensorKind.ACCELERATION -> Sensor.TYPE_ACCELEROMETER
      SensorKind.GRAVITY -> Sensor.TYPE_GRAVITY
      SensorKind.LINEAR_ACCELERATION -> Sensor.TYPE_LINEAR_ACCELERATION
      SensorKind.ANGULAR_VELOCITY -> Sensor.TYPE_GYROSCOPE
      SensorKind.GAME_ROTATION -> Sensor.TYPE_GAME_ROTATION_VECTOR
      SensorKind.PROXIMITY -> Sensor.TYPE_PROXIMITY
      SensorKind.LIGHT -> Sensor.TYPE_LIGHT
    }
