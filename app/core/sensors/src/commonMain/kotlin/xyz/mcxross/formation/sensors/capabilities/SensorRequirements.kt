package xyz.mcxross.formation.sensors.capabilities

import xyz.mcxross.formation.sensors.api.Availability
import xyz.mcxross.formation.sensors.api.SensorSource

enum class InputCapability(val id: String) {
  TILT("sensor.tilt.v1"),
  JOLT("sensor.jolt.v1"),
  COVER("sensor.cover.v1"),
  ROTATION("sensor.rotation.v1"),
  LIGHT("sensor.light.v1"),
}

data class SensorRequirement(
  val capability: InputCapability,
  val allowEstimated: Boolean = true,
  val allowSimulated: Boolean = false,
)

data class CapabilityIssue(val requirement: SensorRequirement, val availability: Availability)

sealed interface Assessment {
  data object Ready : Assessment

  data class Blocked(val issues: List<CapabilityIssue>) : Assessment
}

class CapabilityCatalog(val inputs: Map<InputCapability, Availability>) {
  fun assess(requirements: Collection<SensorRequirement>): Assessment {
    val issues = requirements.mapNotNull { requirement ->
      val availability = inputs.getValue(requirement.capability)
      val accepted =
        availability is Availability.Available &&
          when (availability.source) {
            SensorSource.PLATFORM -> true
            SensorSource.ESTIMATED -> requirement.allowEstimated
            SensorSource.SIMULATED -> requirement.allowSimulated
          }
      if (accepted) null else CapabilityIssue(requirement, availability)
    }
    return if (issues.isEmpty()) Assessment.Ready else Assessment.Blocked(issues)
  }

  fun supported(allowSimulated: Boolean = false): Set<String> =
    inputs.keys
      .filter {
        assess(listOf(SensorRequirement(it, allowSimulated = allowSimulated))) == Assessment.Ready
      }
      .map { it.id }
      .toSet()
}
