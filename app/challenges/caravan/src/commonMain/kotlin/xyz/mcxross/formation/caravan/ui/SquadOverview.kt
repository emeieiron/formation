package xyz.mcxross.formation.caravan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.caravan.CaravanState
import xyz.mcxross.formation.caravan.WalkerState
import xyz.mcxross.formation.caravan.WalkerStatus
import xyz.mcxross.formation.challenge.PlayerView
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.tokens.Radius
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.model.PlayerId

@Composable
internal fun SquadOverview(
  state: CaravanState,
  me: PlayerId,
  players: List<PlayerView>,
  modifier: Modifier = Modifier,
) {
  val colors = Theme.colors

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
    Text(
      "SQUAD MEMBERS (${state.walkers.size})",
      style = Theme.type.caption,
      color = colors.contentSecondary,
    )

    state.walkers.forEach { walker ->
      val playerView = players.firstOrNull { it.id == walker.player }
      val isMe = walker.player == me
      SquadMemberRow(walker, playerView, isMe)
    }
  }
}

@Composable
private fun SquadMemberRow(walker: WalkerState, playerView: PlayerView?, isMe: Boolean) {
  val colors = Theme.colors
  val name = if (isMe) "You" else playerView?.name ?: "Walker"
  val lightColor = playerView?.light?.color ?: colors.accent

  val (statusLabel, statusColor) =
    when (walker.status) {
      WalkerStatus.Finished -> "Done" to colors.positive
      WalkerStatus.WaitingForCaravan -> "Waiting" to colors.warning
      WalkerStatus.Leading -> "Leading" to colors.accent
      WalkerStatus.Lagging -> "Lagging" to colors.warning
      WalkerStatus.Pacing -> "In Pace" to colors.contentSecondary
    }

  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(Radius.xs))
        .background(if (isMe) colors.surfaceHigher else colors.surface)
        .padding(horizontal = Space.m, vertical = Space.s),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(Space.s),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(lightColor))
      Column {
        Text(
          name,
          style = Theme.type.body,
          color = if (isMe) colors.content else colors.contentSecondary,
        )
      }
    }

    Row(
      horizontalArrangement = Arrangement.spacedBy(Space.m),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(statusLabel, style = Theme.type.caption, color = statusColor)
      Text("${walker.steps} st", style = Theme.type.caption, color = colors.content)
    }
  }
}
