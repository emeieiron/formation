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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.mcxross.formation.challenge.Challenge
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.components.ButtonSize
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
import xyz.mcxross.formation.resources.label_funded_rewards
import xyz.mcxross.formation.resources.story_practice_locked
import xyz.mcxross.formation.resources.game_needs_seeker
import xyz.mcxross.formation.resources.action_link_seeker
import xyz.mcxross.formation.state.HardwareCheck
import xyz.mcxross.formation.state.PhoneRole
import xyz.mcxross.formation.ui.LocalGraph
import xyz.mcxross.formation.ui.components.timeLeft

@Composable
internal fun GameActionSheet(
  game: Challenge<*, *>,
  rewards: List<Opportunity>,
  onDismiss: () -> Unit,
  onReward: (Opportunity) -> Unit,
) {
  val graph = LocalGraph.current
  val scope = rememberCoroutineScope()
  val seeker by graph.seeker.identity.collectAsState()
  val hardware by graph.hardware.local.collectAsState()
  val role by graph.role.collectAsState()
  if (game.introduction == null && rewards.isEmpty()) {
    LaunchedEffect(game.id) { onDismiss() }
    return
  }
  // Practice runs on this phone alone, so this phone must be the Seeker; a developer build's pretend Seeker is exempt.
  val pretend = role == PhoneRole.TEST_SEEKER
  if (role == PhoneRole.SEEKER && seeker != null) LaunchedEffect(Unit) { graph.hardware.proveThisPhone() }
  ModalSheet(onDismiss) {
    Column(Modifier.fillMaxWidth().weight(1f, fill = false)
      .verticalScroll(rememberScrollState()).padding(horizontal = Space.xxl)) {
      Spacer(Modifier.height(Space.s))
      Text(game.info.title, style = Theme.type.title2)
      game.introduction?.let { introduction ->
        Spacer(Modifier.height(Space.xl))
        if (pretend || (role == PhoneRole.SEEKER && seeker != null && hardware == HardwareCheck.Proven)) introduction() else {
          HowToPlay(game.info)
          Spacer(Modifier.height(Space.m))
          val failed = hardware as? HardwareCheck.Failed
          Text(
            when {
              role == PhoneRole.PLAYER -> stringResource(Res.string.game_needs_seeker)
              seeker == null -> stringResource(Res.string.story_practice_locked)
              failed != null -> "Only a Seeker can practice. ${failed.message}"
              else -> "Checking this Seeker…"
            },
            style = Theme.type.footnote,
            color = Theme.colors.contentSecondary,
          )
          if (role == PhoneRole.SEEKER && seeker == null) {
            Spacer(Modifier.height(Space.m))
            Button(stringResource(Res.string.action_link_seeker), { scope.launch { graph.seeker.link() } },
              style = ButtonStyle.Reward, size = ButtonSize.Small, fillWidth = false, leadingIcon = Icons.Seeker)
          }
        }
      }
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
