package xyz.mcxross.formation.sensors

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

// On emulators [simulator] stands in for hands: a held pose replaces gravity, gestures mix with the
// real ones.
@OptIn(ExperimentalCoroutinesApi::class)
class MotionSense(motion: Motion, scope: CoroutineScope, private val now: () -> Long) {
  val simulator = MotionSimulator()

  val capabilities: Set<MotionSensor> = motion.available

  // A held pose keeps sampling like a sensor would, so it settles like a real one.
  private val gravity: Flow<Vec3> =
    simulator.gravity.flatMapLatest { held -> if (held != null) steady(held) else motion.gravity }

  private fun steady(g: Vec3): Flow<Vec3> = flow {
    while (true) {
      emit(g)
      delay(HELD_SAMPLE_MS)
    }
  }

  val pose: StateFlow<Pose> = flow {
    val reader = PoseReader()
    gravity.collect { emit(reader.onGravity(it, now())) }
  }
    .distinctUntilChanged()
    .stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), Pose.TILTED)

  val tilt: StateFlow<Tilt> =
    gravity.map(Tilt::of).stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), Tilt.LEVEL)

  val gestures: SharedFlow<Gesture> =
    merge(
        flow {
          val reader = JoltReader()
          motion.linearAcceleration.collect { a ->
            reader.onAcceleration(a, now()).forEach { emit(it) }
          }
        },
        motion.proximity.distinctUntilChanged().filter { it }.map { Gesture.COVER },
        simulator.gestures,
      )
      .shareIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS))

  private companion object {
    const val STOP_AFTER_MS = 1_500L
    const val HELD_SAMPLE_MS = 50L
  }
}

class MotionSimulator {
  private val held = MutableStateFlow<Vec3?>(null)
  private val performed = MutableSharedFlow<Gesture>(extraBufferCapacity = 16)

  val gravity: StateFlow<Vec3?> = held.asStateFlow()
  val gestures: SharedFlow<Gesture> = performed.asSharedFlow()

  fun hold(pose: Pose) {
    held.value = pose.gravity()
  }

  fun lean(roll: Float) {
    val r = roll.coerceIn(-89f, 89f) * PI.toFloat() / 180f
    held.value = Vec3(sin(r) * STANDARD_GRAVITY, 0f, cos(r) * STANDARD_GRAVITY)
  }

  fun release() {
    held.value = null
  }

  fun perform(gesture: Gesture) {
    performed.tryEmit(gesture)
  }
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
