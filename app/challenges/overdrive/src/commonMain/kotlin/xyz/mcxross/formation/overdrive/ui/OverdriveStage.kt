package xyz.mcxross.formation.overdrive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.challenge.StageScope
import xyz.mcxross.formation.challenge.rememberHostNow
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.icons.Icons

@Composable
internal fun OverdriveStage(scope: StageScope<OverdriveState, Rotate>) {
  val state = scope.state
  val dial = state.dial(scope.me)
  val partner = state.partner(scope.me)
  val player = scope.player(partner.player) ?: return
  val now = rememberHostNow(scope.clock)
  val seconds by remember(state.endsAt, now) {
    derivedStateOf { ((state.endsAt - now.value + 999).coerceAtLeast(0) / 1_000).toInt().coerceAtMost(60) }
  }
  var predicted by remember(scope.round, state.wave) { mutableIntStateOf(dial.turns) }
  val turns = maxOf(dial.turns, predicted)

  LaunchedEffect(state.wave, dial.result) {
    if (dial.result == Catch.Caught) scope.haptics.tick()
  }
  LaunchedEffect(state.lastWave?.wave) {
    state.lastWave?.let { if (it.cleared) scope.haptics.confirm() else scope.haptics.reject() }
  }

  Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 20.dp)
    .padding(top = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
    OverdriveHud(state, seconds)
    PartnerClue(player, partner, state.wave, state.preview)
    DialField(dial, state.wave, turns, now, onRotate = {
      val time = scope.clock.hostNow()
      if (time >= state.waveAt && time < dial.catchAt && dial.result == Catch.Pending) {
        predicted = maxOf(dial.turns, predicted) + 1
        scope.send(Rotate(state.wave, predicted))
        scope.haptics.tick()
      }
    }, modifier = Modifier.fillMaxWidth().weight(1f))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
      Icon(Icons.Tap, "Tap the playfield", tint = Theme.colors.contentSecondary, size = 28.dp)
      Icon(Icons.Refresh, "Rotate clockwise", tint = Theme.colors.contentSecondary, size = 28.dp)
    }
  }
}
