package xyz.mcxross.formation.state.sensors

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import xyz.mcxross.formation.platform.ScreenMeasurement
import xyz.mcxross.formation.platform.ScreenPort
import xyz.mcxross.formation.sensors.SensorHub
import xyz.mcxross.formation.sensors.capabilities.Assessment
import xyz.mcxross.formation.sensors.runtime.PreparationState
import xyz.mcxross.formation.session.FormationClient
import xyz.mcxross.formation.session.ScreenProfile
import xyz.mcxross.formation.session.ScreenRequirement
import xyz.mcxross.formation.session.Stage
import xyz.mcxross.formation.state.ChallengeCatalog

sealed interface ScreenReadiness {
  data object NotNeeded : ScreenReadiness

  data class Ready(val profile: ScreenProfile) : ScreenReadiness

  data class TooSmall(val profile: ScreenProfile) : ScreenReadiness

  data object NeedsCalibration : ScreenReadiness

  data object NotFullScreen : ScreenReadiness

  data object Unsupported : ScreenReadiness

  companion object {
    fun of(requirement: ScreenRequirement?, measurement: ScreenMeasurement): ScreenReadiness = when {
      requirement == null -> NotNeeded
      measurement is ScreenMeasurement.Measured ->
        if (requirement.accepts(measurement.profile)) Ready(measurement.profile) else TooSmall(measurement.profile)
      measurement is ScreenMeasurement.NeedsCalibration -> NeedsCalibration
      measurement is ScreenMeasurement.NotFullScreen -> NotFullScreen
      else -> Unsupported
    }
  }
}

class SessionSensors(
  private val client: FormationClient,
  private val hub: SensorHub,
  private val screen: ScreenPort,
  scope: CoroutineScope,
  allowSimulated: Boolean,
) {
  private val state = MutableStateFlow<PreparationState>(PreparationState.Starting)
  val readiness = state.asStateFlow()
  private val screenState = MutableStateFlow<ScreenReadiness>(ScreenReadiness.NotNeeded)
  val screenReadiness = screenState.asStateFlow()

  init {
    scope.launch {
      client.snapshot.map { snapshot ->
        snapshot?.takeIf { it.stage == Stage.Lobby || it.stage is Stage.Briefing || it.stage is Stage.Playing }
          ?.let { ChallengeCatalog[it.formation.opportunity.challenge] to it.formation.opportunity.players }
      }.distinctUntilChanged().collectLatest { selected ->
        val challenge = selected?.first ?: return@collectLatest
        state.value = PreparationState.Starting
        val players = selected.second
        val mandatorySensors = challenge.requiredSensors(players).map {
          it.copy(allowSimulated = it.allowSimulated && allowSimulated)
        }
        val optionalSensors = challenge.optionalSensors(players).map {
          it.copy(allowSimulated = it.allowSimulated && allowSimulated)
        }.filter { hub.assess(listOf(it)) == Assessment.Ready }
        val required = mandatorySensors.map { it.capability.id }
        val requirement = challenge.screenRequirement(players)
        val preparations = (mandatorySensors + optionalSensors).distinctBy { it.capability }.associate {
          it.capability.id to hub.prepare(listOf(it))
        }
        val states = if (preparations.isEmpty()) flowOf(emptyList())
          else combine(preparations.values.map { it.readiness }) { it.toList() }
        val screens = if (requirement == null) flowOf<ScreenReadiness>(ScreenReadiness.NotNeeded)
          else screen.measurement.map { ScreenReadiness.of(requirement, it) }
        try {
          combine(states, screens, client.snapshot) { values, screenNow, snapshot ->
            val byInput = preparations.keys.zip(values).toMap()
            val ready = screenNow as? ScreenReadiness.Ready
            val available = byInput.filterValues { it == PreparationState.Ready }.keys.toSet() +
              listOfNotNull(ready?.let { ScreenRequirement.CAPABILITY })
            val mandatory = required.mapNotNull { byInput[it] }
            val next = mandatory.firstOrNull { it is PreparationState.Blocked || it is PreparationState.Interrupted }
              ?: if (mandatory.all { it == PreparationState.Ready }) PreparationState.Ready else PreparationState.Starting
            SensorReport(snapshot?.round ?: 0, snapshot?.stage, available, next, screenNow)
          }.distinctUntilChanged().collect { report ->
            state.value = report.state
            screenState.value = report.screen
            client.sensors(report.round, report.available, (report.screen as? ScreenReadiness.Ready)?.profile)
          }
        } finally { preparations.values.forEach { it.close() } }
      }
    }
  }

  fun retry() { hub.retry() }
}

private data class SensorReport(
  val round: Int,
  val stage: Stage?,
  val available: Set<String>,
  val state: PreparationState,
  val screen: ScreenReadiness,
)
