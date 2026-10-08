package xyz.mcxross.formation.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import xyz.mcxross.formation.sensors.api.BackendUpdate
import xyz.mcxross.formation.sensors.api.SensorKind
import xyz.mcxross.formation.sensors.api.SensorQuality

internal fun SensorEvent.reading(kind: SensorKind, sensor: Sensor): BackendUpdate.Reading {
  val copied =
    if (kind == SensorKind.PROXIMITY) listOf(if (values[0] < sensor.maximumRange) 1f else 0f)
    else values.toList()
  val quality =
    when {
      kind == SensorKind.PROXIMITY || kind == SensorKind.LIGHT -> SensorQuality.UNKNOWN
      accuracy == 3 -> SensorQuality.USABLE
      accuracy == 1 || accuracy == 2 -> SensorQuality.DEGRADED
      accuracy == 0 -> SensorQuality.UNRELIABLE
      else -> SensorQuality.UNKNOWN
    }
  return BackendUpdate.Reading(copied, timestamp, quality)
}
