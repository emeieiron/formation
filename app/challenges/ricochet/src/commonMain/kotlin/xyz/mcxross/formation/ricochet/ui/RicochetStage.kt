package xyz.mcxross.formation.ricochet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.challenge.StageScope
import xyz.mcxross.formation.challenge.rememberHostNow
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.ricochet.MovePaddle
import xyz.mcxross.formation.ricochet.RicochetState

@Composable
internal fun RicochetStage(scope: StageScope<RicochetState, MovePaddle>) {
  val state = scope.state
  val paddle = state.paddle(scope.me)
  val control = remember(scope.round, scope.me) { PaddleControl(paddle, state.rally) }
  SideEffect { control.reconcile(paddle, state.rally) }
  val now = rememberHostNow(scope.clock)
  val seconds by remember { derivedStateOf {
    val current = scope.state
    val at = (current.finishedAt ?: now.value).coerceAtLeast(current.startAt)
    ((current.endsAt - at).coerceAtLeast(0) + 999).div(1_000).toInt()
  } }
  RicochetFeedback(scope, paddle.side)
  val shown = state.copy(paddles = state.paddles.map { if (it.player == scope.me) it.copy(y = control.y) else it })
  Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)) {
    RicochetHud(state, seconds, now)
    RicochetArena(shown, paddle.side, now, { y, force ->
      control.move(y, scope.state, scope.clock.hostNow(), force, scope::send)
    }, Modifier.fillMaxWidth().weight(1f))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Icon(if (paddle.side == 0) Icons.ArrowLeft else Icons.ArrowRight,
        "Your ${if (paddle.side == 0) "left" else "right"} paddle", tint = Theme.colors.contentSecondary, size = 20.dp)
      Text(scope.player(paddle.player)?.name ?: "", style = Theme.type.overline, color = Theme.colors.contentSecondary)
      Icon(Icons.Tap, "Drag vertically", tint = Theme.colors.contentTertiary, size = 20.dp)
    }
  }
}
