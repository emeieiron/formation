package xyz.mcxross.formation.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.mcxross.formation.challenge.Challenge
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.components.ButtonStyle
import xyz.mcxross.formation.design.components.ModalSheet
import xyz.mcxross.formation.design.components.Overline
import xyz.mcxross.formation.design.components.SheetActions
import xyz.mcxross.formation.design.components.SkrAmount
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Panel
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.foundation.pressable
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.resources.Res
import xyz.mcxross.formation.resources.action_done
import xyz.mcxross.formation.resources.action_view_reward
import xyz.mcxross.formation.resources.copy_game_hosting_requirement
import xyz.mcxross.formation.resources.label_funded_rewards
import xyz.mcxross.formation.ui.components.ChallengeGlyph
import xyz.mcxross.formation.ui.components.timeLeft

@Composable
internal fun GamePreviewSheet(
  game: Challenge<*, *>,
  rewards: List<Opportunity>,
  onDismiss: () -> Unit,
  onReward: (Opportunity) -> Unit,
) {
  val c = Theme.colors
  val info = game.info
  ModalSheet(onDismiss) {
    Column(Modifier.fillMaxWidth().weight(1f, fill = false)
      .verticalScroll(rememberScrollState()).padding(horizontal = Space.xxl)) {
      Spacer(Modifier.height(Space.s))
      Row(verticalAlignment = Alignment.CenterVertically) {
        ChallengeGlyph(info, size = 48.dp)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
          Text(info.title, style = Theme.type.title1)
          Text(info.tagline, style = Theme.type.subhead, color = c.contentSecondary)
          Text(gamePlayerCount(info.players), style = Theme.type.footnote, color = c.contentTertiary)
        }
      }
      game.introduction?.let { introduction ->
        Spacer(Modifier.height(Space.xl))
        introduction()
      }
      Spacer(Modifier.height(Space.xl))
      HowToPlay(info)
      if (rewards.isNotEmpty()) {
        Spacer(Modifier.height(Space.xl))
        Overline(stringResource(Res.string.label_funded_rewards))
        Spacer(Modifier.height(Space.m))
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
          rewards.forEach { reward -> key(reward.id) { FundedRewardRow(reward) { onReward(reward) } } }
        }
      }
    }
    SheetActions {
      if (rewards.isEmpty()) {
        Text(stringResource(Res.string.copy_game_hosting_requirement),
          style = Theme.type.footnote, color = c.contentSecondary)
      }
      Button(stringResource(Res.string.action_done), onDismiss, style = ButtonStyle.Secondary)
    }
  }
}

@Composable
private fun FundedRewardRow(reward: Opportunity, onOpen: () -> Unit) {
  val c = Theme.colors
  val action = stringResource(Res.string.action_view_reward)
  Panel(Modifier.fillMaxWidth().pressable(onOpen, shape = Shapes.card, onClickLabel = action)) {
    Row(Modifier.fillMaxWidth().padding(Space.m), verticalAlignment = Alignment.CenterVertically) {
      SkrAmount(reward.reward.format(0), style = Theme.type.title2, coin = false)
      Spacer(Modifier.width(Space.m))
      Column(Modifier.weight(1f)) {
        Text("${reward.difficulty.label} · ${gamePlayerCount(reward.players..reward.players)}",
          style = Theme.type.footnote, color = c.contentSecondary)
        Text(timeLeft(reward.expiresAt, xyz.mcxross.formation.state.now()),
          style = Theme.type.caption, color = c.contentTertiary)
      }
      Icon(Icons.ChevronRight, null, tint = c.contentSecondary, size = 18.dp)
    }
  }
}
