package xyz.mcxross.formation.caravan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.caravan.CaravanState
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.tokens.Radius
import xyz.mcxross.formation.design.tokens.Space

@Composable
internal fun CaravanHud(state: CaravanState) {
  val colors = Theme.colors
  val remainingSec = (state.timeRemainingMs / 1000L).coerceAtLeast(0L)
  val hours = remainingSec / 3600L
  val minutes = (remainingSec % 3600L) / 60L
  val seconds = remainingSec % 60L
  val timeText =
    if (hours > 0) "${hours}h ${minutes.toString().padStart(2, '0')}m"
    else "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
  val urgent = remainingSec <= 60L

  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s),
    verticalArrangement = Arrangement.spacedBy(Space.s),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column {
        Text("CARAVAN PROGRESS", style = Theme.type.caption, color = colors.contentSecondary)
        Text(
          "${state.minSteps} / ${state.targetSteps} steps",
          style = Theme.type.headline,
          color = colors.content,
        )
      }
      Column(horizontalAlignment = Alignment.End) {
        Text("TIME LEFT", style = Theme.type.caption, color = colors.contentSecondary)
        Text(
          timeText,
          style = Theme.type.numeral,
          color = if (urgent) colors.negative else colors.content,
        )
      }
    }

    // Collective Progress Bar
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .height(8.dp)
          .clip(RoundedCornerShape(Radius.xs))
          .background(colors.surfaceHigh)
    ) {
      Box(
        modifier =
          Modifier.fillMaxWidth(fraction = state.progress)
            .height(8.dp)
            .clip(RoundedCornerShape(Radius.xs))
            .background(colors.accent)
      )
    }
  }
}
