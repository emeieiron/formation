package xyz.mcxross.formation.sensors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.mcxross.formation.sensors.api.SensorKind

class GesturesTest {
  @Test
  fun everyPoseReadsBackFromItsGravity() {
    for (pose in Pose.entries.filter { it != Pose.TILTED }) {
      assertEquals(pose, PoseReader.classify(pose.gravity()), "$pose")
    }
    assertEquals(Pose.TILTED, PoseReader.classify(Pose.TILTED.gravity()))
  }

  @Test
  fun aPoseMustSettleBeforeItCounts() {
    val reader = PoseReader(settleMs = 100)
    assertEquals(Pose.TILTED, reader.onGravity(Pose.FACE_DOWN.gravity(), 0))
    assertEquals(Pose.TILTED, reader.onGravity(Pose.FACE_DOWN.gravity(), 60))
    assertEquals(Pose.FACE_DOWN, reader.onGravity(Pose.FACE_DOWN.gravity(), 120))
    assertEquals(Pose.FACE_DOWN, reader.onGravity(Pose.FACE_UP.gravity(), 150))
    assertEquals(Pose.FACE_DOWN, reader.onGravity(Pose.FACE_DOWN.gravity(), 170))
  }

  @Test
  fun tiltFollowsTheRightEdge() {
    val radians = kotlin.math.PI.toFloat() / 6f
    val tilt =
      Tilt.of(
        Vec3(
          kotlin.math.sin(radians) * STANDARD_GRAVITY,
          0f,
          kotlin.math.cos(radians) * STANDARD_GRAVITY,
        )
      )
    assertEquals(30f, tilt.roll, 0.01f)
    assertEquals(0f, Tilt.of(Pose.FACE_UP.gravity()).roll, 0.01f)
  }

  @Test
  fun threeJoltsMakeAShakeAndOneHardJoltASwing() {
    val reader = JoltReader()
    val quiet = Vec3(0.2f, 0.1f, 0.3f)
    val jolt = Vec3(13f, 0f, 0f)
    val seen = mutableListOf<Pair<Long, Gesture>>()
    var t = 0L
    repeat(3) {
      reader.onAcceleration(jolt, t).forEach { seen += t to it }
      t += 40
      reader.onAcceleration(quiet, t).forEach { seen += t to it }
      t += 140
    }
    assertEquals(listOf(Gesture.SHAKE), seen.map { it.second })

    val swing = JoltReader().onAcceleration(Vec3(0f, 18f, 4f), 5_000)
    assertEquals(listOf(Gesture.SWING), swing)
  }

  @Test
  fun slowJoltsAreNotAShake() {
    val reader = JoltReader()
    val out = mutableListOf<Gesture>()
    for (i in 0 until 3) {
      out += reader.onAcceleration(Vec3(12f, 0f, 0f), i * 1_000L)
      out += reader.onAcceleration(Vec3.ZERO, i * 1_000L + 50)
    }
    assertTrue(out.isEmpty())
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun poseUsesMeasurementTimeAndClearsOnSuspension() = runTest {
    val backend = FakeSensorBackend(SensorKind.GRAVITY)
    val hub = SensorHub(backend, backgroundScope) { testScheduler.currentTime }
    val sense = MotionSense(hub, backgroundScope)
    hub.setForeground(true)
    backgroundScope.launch { sense.pose.collect {} }
    runCurrent()
    val kind = SensorKind.GRAVITY
    backend.emit(kind, 0f, 0f, -9.8f, timestampNanos = 10_000_000)
    runCurrent()
    backend.emit(kind, 0f, 0f, -9.8f, timestampNanos = 170_000_000)
    runCurrent()
    assertEquals(Pose.FACE_DOWN, sense.pose.value)
    hub.setForeground(false)
    runCurrent()
    assertEquals(null, sense.pose.value)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun shakeHistoryCannotCrossAnAcquisitionInterruption() = runTest {
    val backend = FakeSensorBackend(SensorKind.LINEAR_ACCELERATION)
    val hub = SensorHub(backend, backgroundScope) { testScheduler.currentTime }
    val sense = MotionSense(hub, backgroundScope)
    val gestures = mutableListOf<Gesture>()
    hub.setForeground(true)
    backgroundScope.launch { sense.gestures.collect { gestures += it } }
    runCurrent()
    repeat(2) { index ->
      backend.emit(
        SensorKind.LINEAR_ACCELERATION,
        13f,
        0f,
        0f,
        timestampNanos = index * 140_000_000L,
      )
      runCurrent()
      backend.emit(
        SensorKind.LINEAR_ACCELERATION,
        0f,
        0f,
        0f,
        timestampNanos = index * 140_000_000L + 40_000_000,
      )
      runCurrent()
    }
    hub.setForeground(false)
    runCurrent()
    hub.setForeground(true)
    runCurrent()
    backend.emit(SensorKind.LINEAR_ACCELERATION, 13f, 0f, 0f, timestampNanos = 300_000_000)
    runCurrent()
    assertTrue(gestures.isEmpty())
  }
}
