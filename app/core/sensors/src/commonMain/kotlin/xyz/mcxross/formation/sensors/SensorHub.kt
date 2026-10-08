package xyz.mcxross.formation.sensors

import kotlin.math.sqrt
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import xyz.mcxross.formation.sensors.api.*
import xyz.mcxross.formation.sensors.capabilities.*
import xyz.mcxross.formation.sensors.runtime.EstimatedVectorChannel
import xyz.mcxross.formation.sensors.runtime.ManagedSensorChannel
import xyz.mcxross.formation.sensors.runtime.SensorPreparation

class SensorHub(
  private val backend: SensorBackend,
  private val scope: CoroutineScope,
  now: (() -> Long)? = null,
) {
  private val origin = TimeSource.Monotonic.markNow()
  private val clock = now ?: { origin.elapsedNow().inWholeMilliseconds }
  private val managed = mutableListOf<ManagedSensorChannel<*>>()

  private fun <T> channel(kind: SensorKind, decode: (List<Float>) -> T) =
    ManagedSensorChannel(kind, backend, scope, clock, decode).also { managed += it }

  private fun vector(values: List<Float>): Vec3 {
    require(values.size >= 3)
    return Vec3(values[0], values[1], values[2])
  }

  val acceleration: SensorChannel<Vec3> = channel(SensorKind.ACCELERATION, ::vector)
  private val nativeGravity = channel(SensorKind.GRAVITY, ::vector)
  private val nativeLinear = channel(SensorKind.LINEAR_ACCELERATION, ::vector)
  val gravity: SensorChannel<Vec3> =
    if (nativeGravity.availability.value is Availability.Available) nativeGravity
    else EstimatedVectorChannel(acceleration, scope, false)
  val linearAcceleration: SensorChannel<Vec3> =
    if (nativeLinear.availability.value is Availability.Available) nativeLinear
    else EstimatedVectorChannel(acceleration, scope, true)
  val angularVelocity: SensorChannel<Vec3> = channel(SensorKind.ANGULAR_VELOCITY, ::vector)
  val rotation: SensorChannel<Rotation> =
    channel(SensorKind.GAME_ROTATION) { values ->
      require(values.size >= 3)
      Rotation(
        values[0],
        values[1],
        values[2],
        values.getOrNull(3)
          ?: sqrt((1f - values.take(3).sumOf { (it * it).toDouble() }).coerceAtLeast(0.0))
            .toFloat(),
      )
    }
  val proximity: SensorChannel<Boolean> = channel(SensorKind.PROXIMITY) { it.first() > 0.5f }
  val light: SensorChannel<Float> =
    channel(SensorKind.LIGHT) { it.first().also { value -> require(value >= 0f) } }

  private val inputs: Map<InputCapability, SensorChannel<*>> =
    mapOf(
      InputCapability.TILT to gravity,
      InputCapability.JOLT to linearAcceleration,
      InputCapability.COVER to proximity,
      InputCapability.ROTATION to rotation,
      InputCapability.LIGHT to light,
    )
  val capabilities =
    combine(inputs.values.map { it.availability }) { statuses ->
        CapabilityCatalog(inputs.keys.zip(statuses.toList()).toMap())
      }
      .stateIn(
        scope,
        SharingStarted.Eagerly,
        CapabilityCatalog(inputs.mapValues { it.value.availability.value }),
      )

  fun assess(requirements: Collection<SensorRequirement>) = capabilities.value.assess(requirements)

  fun prepare(
    requirements: Collection<SensorRequirement>,
    timeoutMs: Long = 3_000,
  ): SensorPreparation = SensorPreparation(requirements, inputs, scope, timeoutMs)

  fun setForeground(active: Boolean) {
    managed.forEach { it.setForeground(active) }
  }

  fun retry() {
    backend.refresh()
    managed.forEach { it.retry() }
  }
}

class UnsupportedSensorBackend : SensorBackend {
  override val catalog =
    MutableStateFlow(
      SensorKind.entries.associateWith {
        SensorDescriptor(Availability.Unavailable(UnavailableReason.UNSUPPORTED_PLATFORM))
      }
    )

  override fun register(
    kind: SensorKind,
    request: SamplingRequest,
    receive: (BackendUpdate) -> Unit,
  ): SensorRegistration {
    receive(BackendUpdate.Failed(FailureReason.UNAVAILABLE))
    return SensorRegistration {}
  }
}
