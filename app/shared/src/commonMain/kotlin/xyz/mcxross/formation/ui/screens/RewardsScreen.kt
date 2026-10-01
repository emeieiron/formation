package xyz.mcxross.formation.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import xyz.mcxross.formation.resources.Res
import xyz.mcxross.formation.resources.a11y_wallet_address
import xyz.mcxross.formation.resources.action_claim
import xyz.mcxross.formation.resources.copy_rewards_empty
import xyz.mcxross.formation.resources.copy_simulated_rewards
import xyz.mcxross.formation.resources.label_hosted
import xyz.mcxross.formation.resources.label_rewards
import xyz.mcxross.formation.resources.state_no_rewards
import xyz.mcxross.formation.resources.state_reward_claimed
import xyz.mcxross.formation.resources.state_reward_expired
import xyz.mcxross.formation.resources.state_reward_pending
import xyz.mcxross.formation.resources.state_reward_ready
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.LiveryRule
import xyz.mcxross.formation.design.components.RewardPass
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.components.ButtonSize
import xyz.mcxross.formation.design.components.ButtonStyle
import xyz.mcxross.formation.design.components.EmptyState
import xyz.mcxross.formation.design.components.ModalSheet
import xyz.mcxross.formation.design.components.NavigationBarSpacer
import xyz.mcxross.formation.design.components.Notice
import xyz.mcxross.formation.design.components.Overline
import xyz.mcxross.formation.design.components.Page
import xyz.mcxross.formation.design.components.SheetActions
import xyz.mcxross.formation.design.components.SkrAmount
import xyz.mcxross.formation.design.components.TopBar
import xyz.mcxross.formation.design.foundation.Panel
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.design.tokens.Tone
import xyz.mcxross.formation.model.Skr
import xyz.mcxross.formation.state.ClaimTicket
import xyz.mcxross.formation.state.LedgerMode
import xyz.mcxross.formation.state.NO_WALLET
import xyz.mcxross.formation.ui.LocalGraph
import xyz.mcxross.formation.ui.components.ChallengeGlyph
import xyz.mcxross.formation.ui.components.MwaWallets
import xyz.mcxross.formation.ui.components.challengeInfo
import xyz.mcxross.formation.ui.components.rememberWalletInstalled
import xyz.mcxross.formation.ui.components.shortAddress

@Composable
fun RewardsScreen() {
  val graph = LocalGraph.current
  val c = Theme.colors
  val tickets by graph.ledger.tickets.collectAsState()
  var claiming by remember { mutableStateOf<ClaimTicket?>(null) }
  val waiting = tickets.filter { !it.claimed && it.unlocked && !it.lapsed }
  LaunchedEffect(Unit) { graph.ledger.sync() }
  Page(
    topBar = {
      TopBar(title = stringResource(Res.string.label_rewards), onBack = { graph.navigator.pop() })
    },
  ) {
    LiveryRule(Modifier.padding(horizontal = Space.gutter))
    LazyColumn(
      Modifier.fillMaxSize(),
      contentPadding = PaddingValues(horizontal = Space.gutter, vertical = Space.m),
    ) {
      item {
        Column(Modifier.fillMaxWidth()) {
          if (tickets.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxWidth()) {
            val showPass = maxWidth >= 340.dp && LocalDensity.current.fontScale <= 1.15f
            Row(verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Overline(stringResource(Res.string.state_reward_ready), color = c.contentSecondary)
                Spacer(Modifier.height(Space.s))
                SkrAmount(
                  Skr(waiting.sumOf { it.amount.units }).format(2),
                  style = Theme.type.numeralHero,
                  coin = false,
                )
              }
              if (showPass) RewardPass(Modifier.size(width = 112.dp, height = 70.dp))
            }
          }
          Spacer(Modifier.height(Space.l))
          if (graph.ledger.mode == LedgerMode.SIMULATED) {
            Notice(
              stringResource(Res.string.copy_simulated_rewards),
              tone = Tone.Warning,
            )
            Spacer(Modifier.height(Space.l))
          }
        }
      }
      if (tickets.isEmpty()) {
        item {
          EmptyState(
            stringResource(Res.string.state_no_rewards),
            stringResource(Res.string.copy_rewards_empty),
            art = { RewardPass(Modifier.size(width = 144.dp, height = 90.dp)) },
          )
        }
      }
      items(tickets, key = { it.opportunity.value + it.index }) { ticket ->
        TicketCard(ticket, onClaim = { claiming = ticket })
        Spacer(Modifier.height(Space.m))
      }
      item { NavigationBarSpacer() }
    }
  }
  claiming?.let { ClaimSheet(it, onDismiss = { claiming = null }) }
}

@Composable
private fun TicketCard(ticket: ClaimTicket, onClaim: () -> Unit) {
  val c = Theme.colors
  val info = challengeInfo(ticket.challenge)
  val status = when {
    ticket.claimed -> Res.string.state_reward_claimed
    ticket.lapsed -> Res.string.state_reward_expired
    ticket.unlocked -> Res.string.state_reward_ready
    else -> Res.string.state_reward_pending
  }
  val statusIcon = when {
    ticket.claimed -> Icons.Check
    ticket.lapsed -> Icons.Clock
    ticket.unlocked -> Icons.Unlock
    else -> Icons.Lock
  }
  Panel(Modifier.fillMaxWidth()) {
    Column {
      Row(
        Modifier.fillMaxWidth().background(c.inverse)
          .drawBehind { drawRect(c.accent, size = Size(4.dp.toPx(), size.height)) }
          .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
      ) {
        Icon(statusIcon, null, tint = c.onInverse, size = 16.dp)
        Text(
          stringResource(status),
          Modifier.weight(1f),
          style = Theme.type.overline, color = c.onInverse,
        )
      }
      Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          ChallengeGlyph(info, size = 32.dp)
          Spacer(Modifier.width(Space.m))
          Column(Modifier.weight(1f)) {
            Text(info?.title ?: "Formation", style = Theme.type.headline)
            Text(
              if (ticket.index < 0) stringResource(Res.string.label_hosted) else ticket.host,
              style = Theme.type.caption,
              color = c.contentSecondary,
            )
          }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            SkrAmount(
              ticket.amount.format(2),
              style = Theme.type.numeral,
              coin = false,
              color = if (ticket.claimed) c.contentSecondary else c.reward,
              unitColor = c.contentSecondary,
            )
            if (ticket.claimed) {
              Spacer(Modifier.height(Space.xxs))
              val walletLabel = stringResource(Res.string.a11y_wallet_address, ticket.claimedTo.orEmpty())
              Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                Icon(Icons.Wallet, null, size = 14.dp, tint = c.contentSecondary)
                Text(
                  shortAddress(ticket.claimedTo.orEmpty()),
                  Modifier.clearAndSetSemantics { contentDescription = walletLabel },
                  style = Theme.type.caption,
                  color = c.contentSecondary,
                )
              }
            }
          }
          if (!ticket.claimed && !ticket.lapsed && ticket.unlocked) {
            Spacer(Modifier.width(Space.m))
            Button(
              stringResource(Res.string.action_claim),
              onClaim,
              style = ButtonStyle.Reward,
              size = ButtonSize.Small,
              fillWidth = false,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ClaimSheet(ticket: ClaimTicket, onDismiss: () -> Unit) {
  val graph = LocalGraph.current
  val c = Theme.colors
  val scope = rememberCoroutineScope()
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var noWallet by remember { mutableStateOf(false) }
  var done by remember { mutableStateOf<String?>(null) }
  val onPhone = graph.identity.claimAddress
  val canKeepOnPhone = graph.ledger.mode == LedgerMode.SIMULATED || graph.platform.config.debug
  val walletReady = rememberWalletInstalled(graph.platform)
  val needsWallet = noWallet || !walletReady
  LaunchedEffect(walletReady) { if (walletReady) noWallet = false }
  val saved by graph.identity.wallet.collectAsState()
  // A share bound to a wallet at the seal can only go there; otherwise the phone's own wallet.
  val target = ticket.wallet ?: saved

  suspend fun connect(): String? {
    val problem = graph.connectWallet() ?: return graph.identity.wallet.value
    if (problem == NO_WALLET) noWallet = true else error = problem
    return null
  }

  fun claimTo(address: suspend () -> String?) {
    busy = true
    error = null
    scope.launch {
      address()?.let { to ->
        graph.ledger
          .claim(ticket, to)
          .fold(
            onSuccess = { done = to },
            onFailure = { error = it.message ?: "The claim didn't go through" },
          )
      }
      busy = false
    }
  }

  ModalSheet(onDismiss, dismissible = !busy) {
    Column(
      Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = Space.xxl),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Spacer(Modifier.height(Space.m))
      RewardPass(Modifier.size(width = 112.dp, height = 70.dp))
      Spacer(Modifier.height(Space.l))
      Text(
        stringResource(if (done != null) Res.string.state_reward_claimed else Res.string.action_claim),
        style = Theme.type.title1,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(Space.s))
      SkrAmount(
        ticket.amount.format(2),
        style = Theme.type.numeral,
        color = c.reward,
        unitColor = c.reward,
      )
      Spacer(Modifier.height(Space.l))
      Text(
        when {
          done == onPhone -> "It's held by this phone's claim key, ${shortAddress(onPhone)}."
          done != null -> "It's in ${shortAddress(done!!)}."
          needsWallet ->
            "This phone has no Solana wallet yet. Get ${MwaWallets.joinToString(" or ") { it.name }}, then come back: your reward waits for you."
          target != null && ticket.wallet != null ->
            "It goes to ${shortAddress(target)}, the wallet you chose in the lobby."
          target != null -> "It goes to your wallet, ${shortAddress(target)}."
          else ->
            "Connect a Solana wallet to receive it. Your phone proves the share is yours with a key only it holds."
        },
        style = Theme.type.body,
        color = c.contentSecondary,
        textAlign = TextAlign.Center,
      )
      error?.let {
        Spacer(Modifier.height(Space.m))
        Notice(it, tone = Tone.Negative)
      }
      Spacer(Modifier.height(Space.m))
      Overline("Claim key ${shortAddress(graph.identity.claimAddress)}")
    }
    SheetActions {
      when {
        done != null -> Button("Done", onDismiss)
        needsWallet -> {
          MwaWallets.forEachIndexed { i, app ->
            Button(
              "Get ${app.name}",
              { graph.platform.external.openUrl(app.storeUrl) },
              style = if (i == 0) ButtonStyle.Reward else ButtonStyle.Secondary,
              leadingIcon = if (i == 0) Icons.Wallet else Icons.ArrowUpRight,
            )
          }
          if (canKeepOnPhone)
            Button(
              "Keep it on this phone",
              { claimTo { onPhone } },
              style = ButtonStyle.Secondary,
              enabled = !busy,
            )
          Button("Later", onDismiss, style = ButtonStyle.Ghost)
        }
        else -> {
          if (target != null) {
            Button(
              "Claim to ${shortAddress(target)}",
              { claimTo { target } },
              style = ButtonStyle.Reward,
              loading = busy,
              leadingIcon = Icons.Wallet,
            )
            if (ticket.wallet == null)
              Button(
                "Use another wallet",
                { claimTo { connect() } },
                style = ButtonStyle.Ghost,
                enabled = !busy,
              )
          } else {
            Button(
              "Connect wallet",
              { claimTo { connect() } },
              style = ButtonStyle.Reward,
              loading = busy,
              leadingIcon = Icons.Wallet,
            )
          }
          if (canKeepOnPhone)
            Button(
              "Keep it on this phone",
              { claimTo { onPhone } },
              style = ButtonStyle.Ghost,
              enabled = !busy,
            )
        }
      }
    }
  }
}
