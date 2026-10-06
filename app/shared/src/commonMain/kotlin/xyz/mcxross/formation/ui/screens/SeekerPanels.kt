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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.components.ButtonSize
import xyz.mcxross.formation.design.components.ButtonStyle
import xyz.mcxross.formation.design.foundation.Hairline
import xyz.mcxross.formation.design.components.Overline
import xyz.mcxross.formation.design.components.Spinner
import xyz.mcxross.formation.design.components.TextButton
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Panel
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.design.tokens.Tone
import xyz.mcxross.formation.resources.Res
import xyz.mcxross.formation.resources.action_link
import xyz.mcxross.formation.resources.action_link_wallet
import xyz.mcxross.formation.resources.action_pretend_seeker
import xyz.mcxross.formation.resources.join_intro_body
import xyz.mcxross.formation.resources.join_intro_overline
import xyz.mcxross.formation.resources.join_intro_searching
import xyz.mcxross.formation.resources.join_intro_title
import xyz.mcxross.formation.resources.seeker_can_earn
import xyz.mcxross.formation.resources.seeker_can_host
import xyz.mcxross.formation.resources.seeker_can_practice
import xyz.mcxross.formation.resources.seeker_rewards_none
import xyz.mcxross.formation.resources.seeker_rewards_ready
import xyz.mcxross.formation.resources.seeker_setup_body
import xyz.mcxross.formation.resources.seeker_setup_title
import xyz.mcxross.formation.resources.seeker_wallet_link
import xyz.mcxross.formation.resources.seeker_wallet_linked
import xyz.mcxross.formation.resources.seeker_wallet_waiting
import xyz.mcxross.formation.state.SeekerStatus
import xyz.mcxross.formation.ui.components.shortAddress

// Shown to phones that aren't Seekers while no Formation is nearby: what they need, and how to join.
@Composable
internal fun JoinIntro(searching: Boolean, onLink: () -> Unit, onPretend: (() -> Unit)?, modifier: Modifier = Modifier) {
  val c = Theme.colors
  Panel(modifier.fillMaxWidth()) {
    Column(Modifier.padding(Space.l)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Seeker, null, tint = c.accent, size = 18.dp)
        Spacer(Modifier.width(Space.s))
        Overline(stringResource(Res.string.join_intro_overline), color = c.accent)
      }
      Spacer(Modifier.height(Space.s))
      Text(stringResource(Res.string.join_intro_title), style = Theme.type.title2)
      Spacer(Modifier.height(Space.xs))
      Text(stringResource(Res.string.join_intro_body), style = Theme.type.body, color = c.contentSecondary)
      if (searching) {
        Spacer(Modifier.height(Space.m))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Spinner(14.dp, color = c.contentSecondary)
          Spacer(Modifier.width(Space.s))
          Text(stringResource(Res.string.join_intro_searching), style = Theme.type.footnote, color = c.contentSecondary)
        }
      }
      Spacer(Modifier.height(Space.s))
      TextButton(stringResource(Res.string.action_link_wallet), onLink)
      // Developer builds play without a Seeker; release builds never show this.
      onPretend?.let {
        Spacer(Modifier.height(Space.m))
        Hairline()
        Spacer(Modifier.height(Space.m))
        Button(stringResource(Res.string.action_pretend_seeker), it, style = ButtonStyle.Secondary,
          size = ButtonSize.Small, fillWidth = false, leadingIcon = Icons.Seeker)
      }
    }
  }
}

// What a Seeker owner still needs before hosting, and what it unlocks. Hidden once nothing is left to do.
@Composable
internal fun SeekerSetup(
  status: SeekerStatus,
  wallet: String?,
  rewards: Int,
  onLink: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val c = Theme.colors
  Panel(modifier.fillMaxWidth()) {
    Column(Modifier.padding(Space.l)) {
      Text(stringResource(Res.string.seeker_setup_title), style = Theme.type.title2)
      if (wallet == null) {
        Spacer(Modifier.height(Space.xs))
        Text(stringResource(Res.string.seeker_setup_body), style = Theme.type.body, color = c.contentSecondary)
        Spacer(Modifier.height(Space.m))
        SeekerAbilities()
      }
      Spacer(Modifier.height(Space.m))
      Hairline()
      Spacer(Modifier.height(Space.s))
      when {
        wallet != null -> Step(Mark.DONE, stringResource(Res.string.seeker_wallet_linked, shortAddress(wallet)))
        status == SeekerStatus.Checking -> Step(Mark.BUSY, stringResource(Res.string.seeker_wallet_waiting))
        status is SeekerStatus.NeedsApproval -> Step(Mark.PROBLEM, stringResource(Res.string.seeker_wallet_link), status.message,
          stringResource(Res.string.action_link), onLink)
        status is SeekerStatus.NoToken -> Step(Mark.PROBLEM, stringResource(Res.string.seeker_wallet_link),
          "${shortAddress(status.wallet)} doesn't hold a Seeker Genesis Token. Choose the wallet that came with this Seeker.",
          stringResource(Res.string.action_link), onLink)
        else -> Step(Mark.TODO, stringResource(Res.string.seeker_wallet_link), action = stringResource(Res.string.action_link), onAction = onLink)
      }
      if (wallet != null) {
        if (rewards > 0) Step(Mark.DONE, pluralStringResource(Res.plurals.seeker_rewards_ready, rewards, rewards))
        else Step(Mark.TODO, stringResource(Res.string.seeker_rewards_none))
      }
    }
  }
}

@Composable
internal fun SeekerAbilities() {
  Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
    Ability(Icons.Users, stringResource(Res.string.seeker_can_host))
    Ability(Icons.Target, stringResource(Res.string.seeker_can_practice))
    Ability(Icons.Coin, stringResource(Res.string.seeker_can_earn))
  }
}

@Composable
private fun Ability(icon: ImageVector, text: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = Theme.colors.contentSecondary, size = 20.dp)
    Spacer(Modifier.width(Space.m))
    Text(text, style = Theme.type.subhead, modifier = Modifier.weight(1f))
  }
}

private enum class Mark { DONE, BUSY, TODO, PROBLEM }

@Composable
private fun Step(mark: Mark, title: String, detail: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
  val c = Theme.colors
  Row(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalAlignment = Alignment.Top) {
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
      when (mark) {
        Mark.DONE -> Icon(Icons.Check, null, tint = c.positive, size = 18.dp)
        Mark.BUSY -> Spinner(14.dp, color = c.contentSecondary)
        Mark.TODO -> Icon(Icons.ChevronRight, null, tint = c.contentSecondary, size = 18.dp)
        Mark.PROBLEM -> Icon(Icons.Alert, null, tint = c.warning, size = 18.dp)
      }
    }
    Spacer(Modifier.width(Space.m))
    Column(Modifier.weight(1f)) {
      Text(title, style = Theme.type.subheadStrong, color = if (mark == Mark.DONE) c.contentSecondary else c.content)
      detail?.let { Text(it, style = Theme.type.footnote, color = c.contentSecondary) }
    }
    if (action != null && onAction != null)
      TextButton(action, onAction, Modifier.padding(start = Space.s), tone = if (mark == Mark.PROBLEM) Tone.Warning else Tone.Neutral)
  }
}
