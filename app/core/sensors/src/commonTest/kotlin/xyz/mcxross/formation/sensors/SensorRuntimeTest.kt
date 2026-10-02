package xyz.mcxross.formation.sensors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.mcxross.formation.sensors.api.*
import xyz.mcxross.formation.sensors.capabilities.*
import xyz.mcxross.formation.sensors.runtime.PreparationState

@OptIn(ExperimentalCoroutinesApi::class)
class SensorRuntimeTest {
  @Test fun consumersShareRegistrationAndReleaseItAutomatically() = runTest {
    val backend = FakeSensorBackend(SensorKind.ACCELERATION)
    val hub = SensorHub(backend, backgroundScope) { testScheduler.currentTime }
    hub.setForeground(true)
    val first = backgroundScope.launch { hub.acceleration.observe(SamplingRequest.Ui).collect {} }
    runCurrent()
    val second = backgroundScope.launch { hub.acceleration.observe(SamplingRequest.Game).collect {} }
    runCurrent()
    assertEquals(1, backend.active(SensorKind.ACCELERATION))
    assertEquals(listOf(60_000, 20_000), backend.periods)
    second.cancel()
    runCurrent()
    assertEquals(listOf(60_000, 20_000, 60_000), backend.periods)
    first.cancel()
    runCurrent()
    assertEquals(0, backend.active(SensorKind.ACCELERATION))
  }

  @Test fun suspensionDropsOldReadingsAndResumeRequiresNewInput() = runTest {
    val backend = FakeSensorBackend(SensorKind.GRAVITY)
    val hub = SensorHub(backend, backgroundScope) { testScheduler.currentTime }
    hub.setForeground(true)
    val prepared = hub.prepare(listOf(SensorRequirement(InputCapability.TILT)))
    runCurrent()
    backend.emit(SensorKind.GRAVITY, 0f, 0f, 9.8f)
    runCurrent()
    assertEquals(PreparationState.Ready, prepared.readiness.value)
    hub.setForeground(false)
    runCurrent()
    assertEquals(0, backend.active(SensorKind.GRAVITY))
    assertIs<PreparationState.Interrupted>(prepared.readiness.value)
    hub.setForeground(true)
    runCurrent()
    assertEquals(PreparationState.Starting, prepared.readiness.value)
    backend.emit(SensorKind.GRAVITY, 0f, 9.8f, 0f)
    runCurrent()
    assertEquals(PreparationState.Ready, prepared.readiness.value)
    prepared.close()
    runCurrent()
    assertEquals(0, backend.active(SensorKind.GRAVITY))
  }

  @Test fun registrationFailureIsReportedWithoutWaitingForTimeout() = runTest {
    val backend = FakeSensorBackend(SensorKind.PROXIMITY).also { it.rejectRegistration = true }
    val hub = SensorHub(backend, backgroundScope) { testScheduler.currentTime }
    hub.setForeground(true)
    val prepared = hub.prepare(listOf(SensorRequirement(InputCapability.COVER)))
    runCurrent()
    val interrupted = assertIs<PreparationState.Interrupted>(prepared.readiness.value)
    assertEquals(Acquisition.Failed(FailureReason.REGISTRATION), interrupted.acquisition)
    backend.rejectRegistration = false
    hub.retry()
    runCurrent()
    assertEquals(PreparationState.Starting, prepared.readiness.value)
    backend.emit(SensorKind.PROXIMITY, 0f)
    runCurrent()
    assertEquals(PreparationState.Ready, prepared.readiness.value)
    prepared.close()
  }

  @Test fun unchangedProximityRemainsUsableButStoppedMotionFails() = runTest {
    val backend = FakeSensorBackend(SensorKind.GRAVITY, SensorKind.PROXIMITY)
    val hub = SensorHub(backend, backgroundScope) { testScheduler.currentTime }
    hub.setForeground(true)
    val cover = hub.prepare(listOf(SensorRequirement(InputCapability.COVER)))
    val tilt = hub.prepare(listOf(SensorRequirement(InputCapability.TILT)))
    runCurrent()
    backend.emit(SensorKind.PROXIMITY, 0f)
    backend.emit(SensorKind.GRAVITY, 0f, 0f, 9.8f)
    runCurrent()
    advanceTimeBy(5_000)
    runCurrent()
    assertEquals(PreparationState.Ready, cover.readiness.value)
    assertIs<PreparationState.Interrupted>(tilt.readiness.value)
    cover.close()
    tilt.close()
  }

  @Test fun missingHardwareProducesAnActionableAssessment() = runTest {
    val hub = SensorHub(FakeSensorBackend(), backgroundScope)
    val prepared = hub.prepare(listOf(SensorRequirement(InputCapability.COVER)))
    runCurrent()
    val blocked = assertIs<PreparationState.Blocked>(prepared.readiness.value)
    assertEquals(Availability.Unavailable(UnavailableReason.MISSING_HARDWARE), blocked.assessment.issues.single().availability)
    prepared.close()
  }
}
