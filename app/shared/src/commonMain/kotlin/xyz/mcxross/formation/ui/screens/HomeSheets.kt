package xyz.mcxross.formation.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.mcxross.formation.challenge.Challenge
import xyz.mcxross.formation.challenge.ChallengeInfo
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.components.ButtonSize
import xyz.mcxross.formation.design.components.ButtonStyle
import xyz.mcxross.formation.design.components.Chip
import xyz.mcxross.formation.design.components.CodeField
import xyz.mcxross.formation.design.components.DiscoverySignal
import xyz.mcxross.formation.design.components.ModalSheet
import xyz.mcxross.formation.design.components.Overline
import xyz.mcxross.formation.design.components.SheetActions
import xyz.mcxross.formation.design.components.SkrAmount
import xyz.mcxross.formation.design.components.Tag
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Panel
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.model.Budget
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.resources.Res
import xyz.mcxross.formation.resources.action_join
import xyz.mcxross.formation.resources.label_code
import xyz.mcxross.formation.resources.state_found
import xyz.mcxross.formation.resources.state_searching
import xyz.mcxross.formation.session.NearbyFormation
import xyz.mcxross.formation.state.ChallengeCatalog
import xyz.mcxross.formation.state.OpenDraw
import xyz.mcxross.formation.ui.components.ChallengeGlyph
import xyz.mcxross.formation.ui.components.RewardSplitView
import xyz.mcxross.formation.ui.components.TierTag
import xyz.mcxross.formation.ui.components.timeLeft

@Composable
internal fun OpportunitySheet(
  game: Challenge<*, *>,
  budget: Budget,
  onDismiss: () -> Unit,
  onStart: (Opportunity) -> Unit,
) {
  val c = Theme.colors
  val info = game.info
  val sizes = ChallengeCatalog.sizesFor(game.id, budget)
  var players by remember(sizes) { mutableStateOf(sizes.firstOrNull()) }
  val levels = info.difficulties
  var difficulty by remember { mutableStateOf(levels.firstOrNull() ?: Difficulty.NORMAL) }
  var starting by remember { mutableStateOf(false) }
  val o = players?.let { Opportunity(budget, game.id, it, difficulty) }
  ModalSheet(onDismiss, dismissible = !starting) {
    Column(
      Modifier.fillMaxWidth()
        .weight(1f, fill = false)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = Space.xxl)
    ) {
      Spacer(Modifier.height(Space.s))
      Row(verticalAlignment = Alignment.CenterVertically) {
        ChallengeGlyph(info, size = 52.dp)
        Spacer(Modifier.width(Space.l))
        Column(Modifier.weight(1f)) {
          Text(info.title, style = Theme.type.title1)
          Text(info.tagline, style = Theme.type.subhead, color = c.contentSecondary)
        }
        o?.let { TierTag(it.tier) }
      }
      Spacer(Modifier.height(Space.xl))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Lock, null, tint = c.reward, size = 26.dp)
        Spacer(Modifier.width(Space.s))
        SkrAmount(budget.amount.format(0), style = Theme.type.numeralLarge, color = c.content)
      }
      Text(
        "Up to ${budget.maxGuests} guests · ${timeLeft(budget.playUntil, xyz.mcxross.formation.state.now())}",
        style = Theme.type.subhead,
        color = c.contentSecondary,
      )
      if (sizes.size > 1) {
        Spacer(Modifier.height(Space.l))
        Overline("Players")
        Spacer(Modifier.height(Space.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
          sizes.forEach { size -> Chip("$size", size == players, { players = size }) }
        }
      }
      if (levels.size > 1) {
        Spacer(Modifier.height(Space.l))
        Overline("Difficulty")
        Spacer(Modifier.height(Space.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
          levels.forEach { level -> Chip(level.label, level == difficulty, { difficulty = level }) }
        }
      }
      o?.let {
        Spacer(Modifier.height(Space.xl))
        RewardSplitView(it.split(), ownerLabel = "You, the Seeker")
        Spacer(Modifier.height(Space.xl))
        Overline("The goal")
        Spacer(Modifier.height(Space.xs))
        Text(game.goal(it.players, it.difficulty), style = Theme.type.bodyStrong)
      }
      Spacer(Modifier.height(Space.l))
      HowToPlay(info)
      Spacer(Modifier.height(Space.l))
      Text("Sponsored by ${budget.sponsor}", style = Theme.type.footnote, color = c.contentTertiary)
    }
    SheetActions {
      Button(
        "Start Formation",
        {
          starting = true
          o?.let(onStart)
        },
        style = ButtonStyle.Primary,
        loading = starting,
        enabled = o != null,
        trailingIcon = Icons.ArrowRight,
      )
    }
  }
}

@Composable
internal fun HowToPlay(info: ChallengeInfo) {
  val c = Theme.colors
  Overline("How it works")
  Spacer(Modifier.height(Space.m))
  Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
    info.steps.forEachIndexed { i, step ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
          Icon(step.icon, null, tint = c.light(info.light).color, size = 22.dp)
        }
        Spacer(Modifier.width(Space.m))
        Text(
          step.text,
          style = Theme.type.subhead,
          color = c.content,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
  Spacer(Modifier.height(Space.m))
  Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
    info.senses.forEach { Tag(it.label) }
  }
}

@Composable
internal fun JoinCodeSheet(
  nearby: List<NearbyFormation>,
  onDismiss: () -> Unit,
  onJoin: (NearbyFormation) -> Unit,
) {
  val c = Theme.colors
  var code by remember { mutableStateOf("") }
  val match = nearby.firstOrNull { it.beacon.code == code }
  ModalSheet(onDismiss) {
    Column(
      Modifier.fillMaxWidth().padding(horizontal = Space.xxl),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Spacer(Modifier.height(Space.s))
      Text(stringResource(Res.string.label_code), style = Theme.type.title2)
      Spacer(Modifier.height(Space.xl))
      CodeField(code, { code = it }, onDone = { match?.let(onJoin) })
      Spacer(Modifier.height(Space.l))
      when {
        code.length < 4 -> Spacer(Modifier.height(24.dp))
        match != null ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              Icons.Check,
              stringResource(Res.string.state_found),
              tint = c.positive,
              size = 18.dp,
            )
            Spacer(Modifier.width(Space.s))
            Text(match.beacon.host, style = Theme.type.subheadStrong, color = c.positive)
          }
        else ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            DiscoverySignal()
            Spacer(Modifier.width(Space.m))
            Text(
              stringResource(Res.string.state_searching),
              style = Theme.type.subhead,
              color = c.contentSecondary,
            )
          }
      }
    }
    SheetActions {
      Button(
        stringResource(Res.string.action_join),
        { match?.let(onJoin) },
        enabled = match != null && match.beacon.open,
        trailingIcon = Icons.ArrowRight,
      )
    }
  }
}

// A draw the linked Genesis Token can enter; winners each get an equal budget after it closes.
@Composable
internal fun OpenDrawRow(draw: OpenDraw, modifier: Modifier = Modifier, onEnter: () -> Unit) {
  val c = Theme.colors
  var entering by remember(draw.entered) { mutableStateOf(false) }
  Panel(modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().padding(Space.m), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(draw.title ?: "Sponsored draw", style = Theme.type.bodyStrong)
        Row(verticalAlignment = Alignment.CenterVertically) {
          SkrAmount(draw.pool.format(0), style = Theme.type.footnote, coin = false)
          Text(
            " · ${draw.bps / 100}% of entrants win · closes ${timeLeft(draw.enterUntil, xyz.mcxross.formation.state.now())}",
            style = Theme.type.footnote,
            color = c.contentSecondary,
          )
        }
        Text("Sponsored by ${draw.sponsor}", style = Theme.type.caption, color = c.contentTertiary)
      }
      Spacer(Modifier.width(Space.m))
      if (draw.entered) Tag("Entered")
      else
        Button(
          "Enter",
          {
            entering = true
            onEnter()
          },
          style = ButtonStyle.Reward,
          size = ButtonSize.Small,
          fillWidth = false,
          loading = entering,
        )
    }
  }
}
