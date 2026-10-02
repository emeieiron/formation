package xyz.mcxross.formation.sensors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import xyz.mcxross.formation.sensors.api.Availability
import xyz.mcxross.formation.sensors.api.SensorSource
import xyz.mcxross.formation.sensors.api.UnavailableReason
import xyz.mcxross.formation.sensors.capabilities.Assessment
import xyz.mcxross.formation.sensors.capabilities.CapabilityCatalog
import xyz.mcxross.formation.sensors.capabilities.InputCapability
import xyz.mcxross.formation.sensors.capabilities.SensorRequirement

class CapabilityAssessmentTest {
  @Test fun estimatedTiltDoesNotImplyCoverSupport() {
    val catalog = CapabilityCatalog(mapOf(
      InputCapability.TILT to Availability.Available(SensorSource.ESTIMATED),
      InputCapability.COVER to Availability.Unavailable(UnavailableReason.MISSING_HARDWARE),
    ))
    assertEquals(Assessment.Ready, catalog.assess(listOf(SensorRequirement(InputCapability.TILT))))
    assertIs<Assessment.Blocked>(catalog.assess(listOf(SensorRequirement(InputCapability.TILT, allowEstimated = false))))
    assertEquals(setOf(InputCapability.TILT.id), catalog.supported())
  }

  @Test fun simulationRequiresExplicitAcceptance() {
    val catalog = CapabilityCatalog(mapOf(InputCapability.COVER to Availability.Available(SensorSource.SIMULATED)))
    assertIs<Assessment.Blocked>(catalog.assess(listOf(SensorRequirement(InputCapability.COVER))))
    assertEquals(Assessment.Ready, catalog.assess(listOf(SensorRequirement(InputCapability.COVER, allowSimulated = true))))
  }
}
