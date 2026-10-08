package xyz.mcxross.formation.overdrive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
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
  val colors = Theme.colors
  val now = rememberHostNow(scope.clock)
  val fx = rememberStageFx()
  val tenths by remember {
    derivedStateOf {
      val current = scope.state
      val decided =
        current.clears == OverdriveState.REQUIRED_WAVES || current.lastWave?.cleared == false
      val at =
        minOf(
          maxOf(now.value, current.startAt),
          current.lastWave?.at?.takeIf { decided } ?: Long.MAX_VALUE,
        )
      val left = (current.endsAt - at).coerceAtLeast(0)
      (if (left > URGENT_TENTHS * 100L) (left + 999) / 1_000 * 10 else (left + 99) / 100).toInt()
    }
  }
  val ending by remember { derivedStateOf { ending(scope.state, now.value) } }
  var predicted by remember(scope.round, state.wave) { mutableIntStateOf(dial.turns) }
  val turns = if (dial.result == Catch.Pending) maxOf(dial.turns, predicted) else dial.turns

  // A catch and the wave it completes can land in one frame; feel the wave, not both.
  OnChange(Progress(state.wave, dial.result, state.lastWave?.wave)) { old, new ->
    val outcome =
      scope.state.lastWave?.takeIf { new.resolved == new.wave && old.resolved != new.resolved }
    val mine = old.wave == new.wave && old.mine != new.mine
    when {
      outcome?.cleared == true -> scope.haptics.success()
      outcome != null -> if (mine || new.mine == Catch.Caught) scope.haptics.reject()
      mine && new.mine == Catch.Caught -> scope.haptics.heavy()
      mine && new.mine == Catch.Missed -> scope.haptics.reject()
    }
    if (mine && new.mine == Catch.Caught) fx.caught(scope.state.dial(scope.me).facing().color)
    if (mine && new.mine == Catch.Missed) fx.missed()
    if (outcome?.cleared == false) fx.failed()
    if (mine || outcome != null) fx.feltAt = scope.clock.hostNow()
  }
  OnChange(ending) { _, end ->
    when (end) {
      Ending.Won -> fx.won(colors.celebration)
      Ending.Broken -> fx.lost()
      Ending.Timeout -> {
        fx.lost()
        scope.haptics.reject()
      }
      null -> {}
    }
  }
  OnChange((tenths + 9) / 10) { _, seconds ->
    if (
      seconds in 1..HEARTBEAT_SECONDS && ending == null && scope.clock.hostNow() - fx.feltAt > 300
    ) {
      scope.haptics.heartbeat()
    }
  }

  Column(
    Modifier.fillMaxSize()
      .graphicsLayer { translationX = fx.quake.value.dp.toPx() }
      .drawWithContent {
        drawContent()
        if (fx.alarm.value > 0f) drawRect(colors.negative.copy(alpha = 0.24f * fx.alarm.value))
      }
      .navigationBarsPadding()
      .padding(horizontal = 20.dp)
      .padding(top = 12.dp, bottom = 20.dp),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    OverdriveHud(state, tenths)
    PartnerClue(player, partner, state.wave, state.preview, now)
    DialField(
      dial,
      state.wave,
      turns,
      now,
      fx,
      onRotate = {
        val time = scope.clock.hostNow()
        if (time >= state.waveAt && time < dial.catchAt && dial.result == Catch.Pending) {
          predicted = maxOf(dial.turns, predicted) + 1
          scope.send(Rotate(state.wave, predicted, time))
          scope.haptics.tick()
        }
      },
      modifier = Modifier.fillMaxWidth().weight(1f),
    )
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
    ) {
      Icon(Icons.Tap, "Tap the playfield", tint = colors.contentSecondary, size = 28.dp)
      Icon(Icons.Refresh, "Rotate clockwise", tint = colors.contentSecondary, size = 28.dp)
    }
  }
}

private enum class Ending {
  Won,
  Broken,
  Timeout,
}

// A catch that lands on the buzzer resolves just after it, so wait briefly before calling time.
private fun ending(state: OverdriveState, now: Long): Ending? =
  when {
    state.clears == OverdriveState.REQUIRED_WAVES -> Ending.Won
    state.lastWave?.cleared == false -> Ending.Broken
    now >= state.endsAt + 250 -> Ending.Timeout
    else -> null
  }

private data class Progress(val wave: Int, val mine: Catch, val resolved: Int?)

private const val HEARTBEAT_SECONDS = 5
