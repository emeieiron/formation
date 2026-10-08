package xyz.mcxross.formation.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import xyz.mcxross.formation.sensors.api.BackendUpdate
import xyz.mcxross.formation.sensors.api.FailureReason
import xyz.mcxross.formation.sensors.api.SamplingRequest
import xyz.mcxross.formation.sensors.api.SensorBackend
import xyz.mcxross.formation.sensors.api.SensorKind
import xyz.mcxross.formation.sensors.api.SensorRegistration

class AndroidSensorBackend(context: Context) : SensorBackend {
  private val manager = context.getSystemService(SensorManager::class.java)
  private val inventory = AndroidSensorCatalog(manager)
  override val catalog = inventory.catalog

  override fun refresh() {
    inventory.refresh()
  }

  override fun register(
    kind: SensorKind,
    request: SamplingRequest,
    receive: (BackendUpdate) -> Unit,
  ): SensorRegistration {
    val sensor = inventory.sensors[kind]
    if (sensor == null || manager == null) {
      receive(BackendUpdate.Failed(FailureReason.UNAVAILABLE))
      return SensorRegistration {}
    }
    val listener =
      object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
          receive(event.reading(kind, sensor))
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
      }
    val registered =
      try {
        manager.registerListener(
          listener,
          sensor,
          request.periodUs,
          0,
          Handler(Looper.getMainLooper()),
        )
      } catch (_: SecurityException) {
        inventory.restricted(kind)
        false
      } catch (_: RuntimeException) {
        false
      }
    if (!registered) receive(BackendUpdate.Failed(FailureReason.REGISTRATION))
    return SensorRegistration { manager.unregisterListener(listener) }
  }
}
