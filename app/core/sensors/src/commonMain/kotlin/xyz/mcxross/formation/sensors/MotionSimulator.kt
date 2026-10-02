package xyz.mcxross.formation.sensors

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.mcxross.formation.sensors.api.*

class MotionSimulator(private val scope: CoroutineScope) : SensorBackend {
  private val origin = TimeSource.Monotonic.markNow()
  private val held = MutableStateFlow<Vec3>(Pose.TILTED.gravity())
  private val performed = MutableSharedFlow<Gesture>(extraBufferCapacity = 16)
  val gravity: kotlinx.coroutines.flow.StateFlow<Vec3> = held.asStateFlow()
  val gestures = performed.asSharedFlow()
  override val catalog = MutableStateFlow(SensorKind.entries.associateWith {
    SensorDescriptor(Availability.Available(SensorSource.SIMULATED), it != SensorKind.PROXIMITY && it != SensorKind.LIGHT)
  })

  override fun register(kind: SensorKind, request: SamplingRequest, receive: (BackendUpdate) -> Unit): SensorRegistration {
    val job = scope.launch {
      while (true) {
        val g = held.value
        val values = when (kind) {
          SensorKind.ACCELERATION, SensorKind.GRAVITY -> listOf(g.x, g.y, g.z)
          SensorKind.LINEAR_ACCELERATION, SensorKind.ANGULAR_VELOCITY -> listOf(0f, 0f, 0f)
          SensorKind.GAME_ROTATION -> listOf(0f, 0f, 0f, 1f)
          SensorKind.PROXIMITY -> listOf(0f)
          SensorKind.LIGHT -> listOf(100f)
        }
        receive(BackendUpdate.Reading(values, origin.elapsedNow().inWholeNanoseconds, SensorQuality.USABLE))
        delay((request.periodUs / 1_000L).coerceAtLeast(1))
      }
    }
    return SensorRegistration { job.cancel() }
  }

  fun hold(pose: Pose) { held.value = pose.gravity() }
  fun lean(roll: Float) {
    val radians = roll.coerceIn(-89f, 89f) * PI.toFloat() / 180f
    held.value = Vec3(sin(radians) * STANDARD_GRAVITY, 0f, cos(radians) * STANDARD_GRAVITY)
  }
  fun release() { held.value = Pose.TILTED.gravity() }
  fun perform(gesture: Gesture) { performed.tryEmit(gesture) }
}

fun Pose.gravity(): Vec3 {
  val g = STANDARD_GRAVITY
  return when (this) {
    Pose.FACE_UP -> Vec3(0f, 0f, g)
    Pose.FACE_DOWN -> Vec3(0f, 0f, -g)
    Pose.UPRIGHT -> Vec3(0f, g, 0f)
    Pose.UPSIDE_DOWN -> Vec3(0f, -g, 0f)
    Pose.SIDEWAYS_LEFT -> Vec3(g, 0f, 0f)
    Pose.SIDEWAYS_RIGHT -> Vec3(-g, 0f, 0f)
    Pose.TILTED -> Vec3(0f, g * 0.7071f, g * 0.7071f)
  }
}
