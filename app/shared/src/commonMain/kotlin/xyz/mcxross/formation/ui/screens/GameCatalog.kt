package xyz.mcxross.formation.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import xyz.mcxross.formation.challenge.Challenge
import xyz.mcxross.formation.challenge.ChallengeInfo
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.SectionHeader
import xyz.mcxross.formation.design.components.liveryCard
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Panel
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.foundation.pressable
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.resources.Res
import xyz.mcxross.formation.resources.label_games
import xyz.mcxross.formation.resources.player_count
import xyz.mcxross.formation.resources.player_range
import xyz.mcxross.formation.resources.state_no_games
import xyz.mcxross.formation.ui.components.ChallengeGlyph

internal fun LazyListScope.gameCatalog(games: List<Challenge<*, *>>, onOpen: (Challenge<*, *>) -> Unit) {
  item(key = "games-header") { SectionHeader(stringResource(Res.string.label_games)) }
  if (games.isEmpty()) {
    item(key = "games-empty") {
      Text(stringResource(Res.string.state_no_games), Modifier.padding(horizontal = Space.gutter),
        style = Theme.type.subhead, color = Theme.colors.contentSecondary)
    }
  }
  items(games, key = { "game-${it.id.value}" }) { game -> GameCard(game.info) { onOpen(game) } }
}

@Composable
private fun GameCard(info: ChallengeInfo, onOpen: () -> Unit) {
  val c = Theme.colors
  Panel(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.xs)
    .pressable(onOpen, shape = Shapes.card, travel = true).liveryCard()) {
    Row(Modifier.fillMaxWidth().padding(Space.l), verticalAlignment = Alignment.CenterVertically) {
      ChallengeGlyph(info, size = 40.dp)
      Spacer(Modifier.width(Space.m))
      Column(Modifier.weight(1f)) {
        Text(info.title, style = Theme.type.headline)
        Text(info.tagline, style = Theme.type.footnote, color = c.contentSecondary)
        Text(gamePlayerCount(info.players), style = Theme.type.caption, color = c.contentTertiary)
      }
      Spacer(Modifier.width(Space.s))
      Icon(Icons.ChevronRight, null, tint = c.contentSecondary, size = 20.dp)
    }
  }
}

@Composable
internal fun gamePlayerCount(players: IntRange): String =
  if (players.first == players.last)
    pluralStringResource(Res.plurals.player_count, players.first, players.first)
  else stringResource(Res.string.player_range, players.first, players.last)
