package xyz.mcxross.formation.caravan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.mcxross.formation.sensors.STANDARD_GRAVITY
import xyz.mcxross.formation.sensors.Vec3

class StepDetectorTest {
  @Test
  fun stationaryNoiseDoesNotCountAndSteadyPeaksCountOnce() {
    val detector = StepDetector()
    for (at in 1_000L..2_000L step 20L) {
      assertFalse(detector.onLinearAcceleration(Vec3(0.3f, 0.4f, 0.2f), at))
    }
    var counted = 0
    repeat(10) { step ->
      val at = 3_000L + step * 600L
      for ((offset, value) in listOf(0L to 0f, 40L to 3f, 80L to 1f, 120L to 0f)) {
        if (detector.onLinearAcceleration(Vec3(value, 0f, 0f), at + offset)) counted++
      }
    }
    assertEquals(10, counted)
  }

  @Test
  fun rapidPeaksAreRejectedAndDetectionRecovers() {
    val detector = StepDetector()
    assertFalse(detector.onLinearAcceleration(Vec3(0f, 3f, 0f), 1_000L))
    assertTrue(detector.onLinearAcceleration(Vec3(0f, 1f, 0f), 1_040L))
    assertFalse(detector.onLinearAcceleration(Vec3(0f, 3f, 0f), 1_100L))
    assertFalse(detector.onLinearAcceleration(Vec3(0f, 1f, 0f), 1_140L))
    assertFalse(detector.onLinearAcceleration(Vec3(0f, 0f, 3f), 1_600L))
    assertTrue(detector.onLinearAcceleration(Vec3(0f, 0f, 1f), 1_640L))
  }

  @Test
  fun gravityFallbackDoesNotCountRestingAcceleration() {
    val detector = StepDetector()
    assertFalse(detector.onAcceleration(Vec3(0f, 0f, STANDARD_GRAVITY), 1_000L))
    assertFalse(detector.onAcceleration(Vec3(0f, 0f, STANDARD_GRAVITY + 3f), 1_600L))
    assertTrue(detector.onAcceleration(Vec3(0f, 0f, STANDARD_GRAVITY + 1f), 1_640L))
    detector.reset()
    assertFalse(detector.onLinearAcceleration(Vec3(0f, 0f, 0f), 2_000L))
    assertFalse(detector.onLinearAcceleration(Vec3(3f, 0f, 0f), 2_040L))
    assertTrue(detector.onLinearAcceleration(Vec3(1f, 0f, 0f), 2_080L))
  }
}
