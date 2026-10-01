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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.challenge.ChallengeInfo
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.components.ButtonStyle
import xyz.mcxross.formation.design.components.CodeField
import xyz.mcxross.formation.design.components.ModalSheet
import xyz.mcxross.formation.design.components.Overline
import xyz.mcxross.formation.design.components.SheetActions
import xyz.mcxross.formation.design.components.SkrAmount
import xyz.mcxross.formation.design.components.Spinner
import xyz.mcxross.formation.design.components.Tag
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.session.NearbyFormation
import xyz.mcxross.formation.state.ChallengeCatalog
import xyz.mcxross.formation.ui.components.ChallengeGlyph
import xyz.mcxross.formation.ui.components.RewardSplitView
import xyz.mcxross.formation.ui.components.TierTag
import xyz.mcxross.formation.ui.components.timeLeft

@Composable
internal fun OpportunitySheet(o: Opportunity, onDismiss: () -> Unit, onStart: () -> Unit) {
  val c = Theme.colors
  val challenge = ChallengeCatalog[o.challenge]
  val info = challenge?.info
  var starting by remember { mutableStateOf(false) }
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
          Text(info?.title ?: o.challenge.value, style = Theme.type.title1)
          Text(info?.tagline ?: "", style = Theme.type.subhead, color = c.contentSecondary)
        }
        TierTag(o.tier)
      }
      Spacer(Modifier.height(Space.xl))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Lock, null, tint = c.reward, size = 26.dp)
        Spacer(Modifier.width(Space.s))
        SkrAmount(o.reward.format(0), style = Theme.type.numeralLarge, color = c.content)
      }
      Text(
        "Unlocks with ${o.players} players · ${timeLeft(o.expiresAt, xyz.mcxross.formation.state.now())}",
        style = Theme.type.subhead,
        color = c.contentSecondary,
      )
      Spacer(Modifier.height(Space.xl))
      RewardSplitView(o.split(), ownerLabel = "You, the Seeker")
      Spacer(Modifier.height(Space.xl))
      challenge?.let {
        Overline("The goal")
        Spacer(Modifier.height(Space.xs))
        Text(it.goal(o.players, o.difficulty), style = Theme.type.bodyStrong)
        Spacer(Modifier.height(Space.l))
        HowToPlay(it.info)
      }
        ?: Text(
          "This app doesn't know this challenge yet. Update to host it.",
          style = Theme.type.body,
          color = c.warning,
        )
      Spacer(Modifier.height(Space.l))
      Text("Sponsored by ${o.sponsor}", style = Theme.type.footnote, color = c.contentTertiary)
    }
    SheetActions {
      Button(
        "Start Formation",
        {
          starting = true
          onStart()
        },
        style = ButtonStyle.Primary,
        loading = starting,
        enabled = challenge != null,
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
      Text("Enter the code", style = Theme.type.title2)
      Spacer(Modifier.height(Space.xs))
      Text(
        "It's on the Seeker's screen, under the QR code.",
        style = Theme.type.subhead,
        color = c.contentSecondary,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(Space.xl))
      CodeField(code, { code = it }, onDone = { match?.let(onJoin) })
      Spacer(Modifier.height(Space.l))
      when {
        code.length < 4 -> Spacer(Modifier.height(24.dp))
        match != null ->
          Text(
            "Found ${match.beacon.host}'s Formation",
            style = Theme.type.subheadStrong,
            color = c.positive,
          )
        else ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            Spinner(16.dp, color = c.contentSecondary)
            Spacer(Modifier.width(Space.s))
            Text(
              "Looking for $code on this network…",
              style = Theme.type.subhead,
              color = c.contentSecondary,
            )
          }
      }
    }
    SheetActions {
      Button(
        "Join",
        { match?.let(onJoin) },
        enabled = match != null && match.beacon.open,
        trailingIcon = Icons.ArrowRight,
      )
    }
  }
}
