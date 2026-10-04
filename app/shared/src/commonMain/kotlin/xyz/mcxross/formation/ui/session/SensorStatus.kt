package xyz.mcxross.formation.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.TextButton
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.resources.*
import xyz.mcxross.formation.sensors.api.Acquisition
import xyz.mcxross.formation.sensors.api.Availability
import xyz.mcxross.formation.sensors.api.UnavailableReason
import xyz.mcxross.formation.sensors.capabilities.InputCapability
import xyz.mcxross.formation.sensors.runtime.PreparationState
import xyz.mcxross.formation.state.ActiveSession
import xyz.mcxross.formation.state.sensors.ScreenReadiness
import xyz.mcxross.formation.ui.LocalGraph

@Composable
internal fun SensorStatus(session: ActiveSession, modifier: Modifier = Modifier) {
  val monitor = session.sensors ?: return
  val state by monitor.readiness.collectAsState()
  val screen by monitor.screenReadiness.collectAsState()
  val snapshot by session.snapshot.collectAsState()
  val challenge = session.challenge ?: return
  val count = snapshot?.formation?.opportunity?.players ?: return
  if (challenge.requiredCapabilities(count).isEmpty() && challenge.optionalCapabilities(count).isEmpty()) return
  val c = Theme.colors
  var calibrating by remember { mutableStateOf(false) }
  val unavailable = (state as? PreparationState.Blocked)?.assessment?.issues
    ?.mapNotNull { it.availability as? Availability.Unavailable }?.firstOrNull()
  val screenProblem = when (screen) {
    ScreenReadiness.NotNeeded, is ScreenReadiness.Ready -> null
    is ScreenReadiness.TooSmall -> Res.string.screen_too_small
    ScreenReadiness.NeedsCalibration -> Res.string.screen_calibrate
    ScreenReadiness.NotFullScreen -> Res.string.screen_full
    ScreenReadiness.Unsupported -> Res.string.screen_unsupported
  }
  val resource = screenProblem ?: when (val current = state) {
    PreparationState.Ready ->
      if (screen is ScreenReadiness.Ready && challenge.requiredSensors(count).isEmpty()) Res.string.screen_ready
      else Res.string.sensor_ready
    PreparationState.Starting -> Res.string.sensor_checking
    is PreparationState.Interrupted -> if (current.acquisition == Acquisition.Suspended) Res.string.sensor_paused else Res.string.sensor_failed
    is PreparationState.Blocked -> if (unavailable?.reason == UnavailableReason.SYSTEM_RESTRICTION ||
      unavailable?.reason == UnavailableReason.PERMISSION_REQUIRED) Res.string.sensor_access_restricted
    else when (current.assessment.issues.firstOrNull()?.requirement?.capability) {
      InputCapability.COVER -> Res.string.sensor_cover_unavailable
      InputCapability.ROTATION -> Res.string.sensor_rotation_unavailable
      InputCapability.LIGHT -> Res.string.sensor_light_unavailable
      else -> Res.string.sensor_motion_unavailable
    }
  }
  val ready = state == PreparationState.Ready && screenProblem == null
  Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
    Icon(if (ready) Icons.Check else Icons.Seeker, null, tint = if (ready) c.positive else c.warning, size = 16.dp)
    Text(stringResource(resource), Modifier.weight(1f), style = Theme.type.footnote, color = c.contentSecondary)
    if (screen == ScreenReadiness.NeedsCalibration || screen is ScreenReadiness.TooSmall)
      TextButton(stringResource(Res.string.screen_measure), { calibrating = true })
    else if ((state as? PreparationState.Interrupted)?.acquisition is Acquisition.Failed || unavailable?.recovery != null)
      TextButton(stringResource(Res.string.sensor_retry), monitor::retry)
  }
  if (calibrating) ScreenCalibration(LocalGraph.current.platform.screen) { calibrating = false }
}
