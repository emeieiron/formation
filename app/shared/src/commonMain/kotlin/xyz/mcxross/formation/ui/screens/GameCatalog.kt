package xyz.mcxross.formation.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import xyz.mcxross.formation.challenge.Challenge
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.CardDeck
import xyz.mcxross.formation.design.components.IconButton
import xyz.mcxross.formation.design.components.IconButtonStyle
import xyz.mcxross.formation.design.components.SectionHeader
import xyz.mcxross.formation.design.components.Tag
import xyz.mcxross.formation.design.components.liveryCard
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Panel
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.foundation.pressable
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Motion
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.design.tokens.Tone
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.resources.Res
import xyz.mcxross.formation.resources.action_next_game
import xyz.mcxross.formation.resources.action_previous_game
import xyz.mcxross.formation.resources.action_game_rewards
import xyz.mcxross.formation.resources.action_practice_game
import xyz.mcxross.formation.resources.action_select_game
import xyz.mcxross.formation.resources.a11y_game_position
import xyz.mcxross.formation.resources.label_games
import xyz.mcxross.formation.resources.label_test_host
import xyz.mcxross.formation.resources.player_count
import xyz.mcxross.formation.resources.player_range
import xyz.mcxross.formation.resources.state_no_games
import xyz.mcxross.formation.resources.state_game_playable
import xyz.mcxross.formation.resources.state_game_no_reward
import xyz.mcxross.formation.resources.state_game_join

internal fun LazyListScope.gameCatalog(
  games: List<Challenge<*, *>>,
  canHost: Boolean,
  playable: Set<ChallengeId>,
  testHost: Boolean,
  onOpen: (Challenge<*, *>) -> Unit,
) {
  item(key = "games-header") {
    SectionHeader(stringResource(Res.string.label_games), Modifier.padding(end = Space.m), trailing = {
      if (testHost) Tag(stringResource(Res.string.label_test_host), tone = Tone.Warning)
    })
  }
  item(key = "games-catalog") {
    if (games.isEmpty()) {
      Text(stringResource(Res.string.state_no_games), Modifier.padding(horizontal = Space.gutter),
        style = Theme.type.subhead, color = Theme.colors.contentSecondary)
    } else GameCatalog(games, canHost, playable, onOpen)
  }
}

@Composable
private fun GameCatalog(games: List<Challenge<*, *>>, canHost: Boolean, playable: Set<ChallengeId>, onOpen: (Challenge<*, *>) -> Unit) {
  val state = rememberPagerState { games.size }
  val scope = rememberCoroutineScope()
  val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  fun select(page: Int) {
    if (!state.isScrollInProgress) scope.launch { state.animateScrollToPage(page, animationSpec = Motion.snappy()) }
  }
  Column {
    CardDeck(state, key = { games[it].id.value }) { page, modifier ->
      val game = games[page]
      GameCard(game, canHost, game.id in playable, page == state.settledPage, modifier) {
        if (!state.isScrollInProgress) {
          if (page == state.settledPage) onOpen(game) else select(page)
        }
      }
    }
    if (games.size > 1) {
      val position = stringResource(Res.string.a11y_game_position, state.settledPage + 1, games.size)
      Row(Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.gutter, top = Space.s),
        verticalAlignment = Alignment.CenterVertically) {
        Text("${(state.settledPage + 1).toString().padStart(2, '0')} / ${games.size.toString().padStart(2, '0')}",
          Modifier.weight(1f).clearAndSetSemantics { contentDescription = position },
          style = Theme.type.code, color = Theme.colors.contentTertiary)
        IconButton(if (rtl) Icons.ArrowRight else Icons.ArrowLeft, stringResource(Res.string.action_previous_game), { select(state.settledPage - 1) },
          style = IconButtonStyle.Ghost, size = 48.dp, enabled = state.settledPage > 0 && !state.isScrollInProgress)
        IconButton(if (rtl) Icons.ArrowLeft else Icons.ArrowRight, stringResource(Res.string.action_next_game), { select(state.settledPage + 1) },
          style = IconButtonStyle.Ghost, size = 48.dp, enabled = state.settledPage < games.lastIndex && !state.isScrollInProgress)
      }
    }
    GameGuide(games[state.settledPage].info)
  }
}

@Composable
private fun GameCard(game: Challenge<*, *>, canHost: Boolean, playable: Boolean, focused: Boolean, modifier: Modifier, onOpen: () -> Unit) {
  val c = Theme.colors
  val info = game.info
  val canOpen = playable || game.introduction != null
  val action = stringResource(when {
    !focused -> Res.string.action_select_game
    playable -> Res.string.action_game_rewards
    else -> Res.string.action_practice_game
  }, info.title)
  val interaction = if (!focused || canOpen)
    Modifier.pressable(onOpen, shape = Shapes.card, travel = true, onClickLabel = action)
  else Modifier
  Panel(modifier.then(interaction).semantics(mergeDescendants = true) { selected = focused }.liveryCard()) {
    Column(Modifier.fillMaxWidth()) {
      Row(Modifier.fillMaxWidth().padding(start = Space.l, end = Space.l, top = Space.l),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
          Tag(stringResource(when {
            playable -> Res.string.state_game_playable
            canHost -> Res.string.state_game_no_reward
            else -> Res.string.state_game_join
          }), tone = if (playable) Tone.Positive else Tone.Neutral,
            icon = if (playable) Icons.Check else null)
        }
        Text(gamePlayerCount(info.players), style = Theme.type.caption, color = c.contentSecondary)
      }
      Box(Modifier.fillMaxWidth().height(150.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        game.cover?.invoke() ?: Icon(info.icon, null, tint = c.accent, size = 80.dp)
      }
      Column(Modifier.fillMaxWidth().padding(horizontal = Space.l)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(info.title, Modifier.weight(1f), style = Theme.type.title1, maxLines = 2)
          if (canOpen) Icon(Icons.ArrowUpRight, null, tint = c.contentSecondary)
        }
        Text(info.tagline, style = Theme.type.footnote, color = c.contentSecondary, maxLines = 2)
        Spacer(Modifier.height(Space.l))
      }
    }
  }
}

@Composable
internal fun gamePlayerCount(players: IntRange): String =
  if (players.first == players.last)
    pluralStringResource(Res.plurals.player_count, players.first, players.first)
  else stringResource(Res.string.player_range, players.first, players.last)
