package xyz.mcxross.formation.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

class AndroidMotion(context: Context) : Motion {
  private val manager = context.getSystemService(SensorManager::class.java)
  private val accelerometer: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
  private val gravitySensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
  private val linearSensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
  private val proximitySensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
  private val gyroscope: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

  override val available: Set<MotionSensor> = buildSet {
    if (accelerometer != null) add(MotionSensor.ACCELEROMETER)
    if (gyroscope != null) add(MotionSensor.GYROSCOPE)
    if (proximitySensor != null) add(MotionSensor.PROXIMITY)
  }

  override val gravity: Flow<Vec3> =
    when {
      gravitySensor != null -> readings(gravitySensor).map { Vec3(it[0], it[1], it[2]) }
      accelerometer != null ->
        flow {
          var g: Vec3? = null
          readings(accelerometer).collect { a ->
            val prev = g
            val next =
              if (prev == null) Vec3(a[0], a[1], a[2])
              else Vec3(lerp(prev.x, a[0]), lerp(prev.y, a[1]), lerp(prev.z, a[2]))
            g = next
            emit(next)
          }
        }
      else -> emptyFlow()
    }

  override val linearAcceleration: Flow<Vec3> =
    when {
      linearSensor != null -> readings(linearSensor).map { Vec3(it[0], it[1], it[2]) }
      accelerometer != null ->
        flow {
          var g: Vec3? = null
          readings(accelerometer).collect { a ->
            val prev = g ?: Vec3(a[0], a[1], a[2])
            val next = Vec3(lerp(prev.x, a[0]), lerp(prev.y, a[1]), lerp(prev.z, a[2]))
            g = next
            emit(Vec3(a[0] - next.x, a[1] - next.y, a[2] - next.z))
          }
        }
      else -> emptyFlow()
    }

  override val proximity: Flow<Boolean> =
    proximitySensor?.let { sensor ->
      // Most proximity sensors are binary: 0 when covered, their maximum range when not.
      readings(sensor, SensorManager.SENSOR_DELAY_NORMAL).map { it[0] < sensor.maximumRange * 0.5f }
    } ?: emptyFlow()

  private fun readings(
    sensor: Sensor,
    rate: Int = SensorManager.SENSOR_DELAY_GAME,
  ): Flow<FloatArray> = callbackFlow {
    val listener =
      object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
          trySend(event.values.copyOf())
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
      }
    manager?.registerListener(listener, sensor, rate)
    awaitClose { manager?.unregisterListener(listener) }
  }
    .buffer(64, BufferOverflow.DROP_OLDEST)

  private fun lerp(from: Float, to: Float) = from + LOW_PASS * (to - from)

  private companion object {
    const val LOW_PASS = 0.2f
  }
}
