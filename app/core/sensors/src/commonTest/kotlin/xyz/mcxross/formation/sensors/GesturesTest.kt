package xyz.mcxross.formation.sensors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

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
    val sim = MotionSimulator()
    sim.lean(30f)
    val tilt = Tilt.of(sim.gravity.value!!)
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
  fun theSimulatorDrivesPoseAndGestures() =
    runTest(UnconfinedTestDispatcher()) {
      val sense = MotionSense(NoMotion, backgroundScope) { testScheduler.currentTime }
      val gestures = mutableListOf<Gesture>()
      backgroundScope.launch { sense.gestures.collect { gestures += it } }
      backgroundScope.launch { sense.pose.collect {} }

      sense.simulator.hold(Pose.FACE_DOWN)
      advanceTimeBy(400)
      assertEquals(Pose.FACE_DOWN, sense.pose.value)

      sense.simulator.hold(Pose.SIDEWAYS_LEFT)
      advanceTimeBy(400)
      assertEquals(Pose.SIDEWAYS_LEFT, sense.pose.value)

      sense.simulator.perform(Gesture.SHAKE)
      advanceUntilIdle()
      assertEquals(listOf(Gesture.SHAKE), gestures)
      assertEquals(Pose.SIDEWAYS_LEFT.gravity(), sense.simulator.gravity.first())
    }
}
