package xyz.mcxross.formation.ui.screens

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.mcxross.formation.design.Theme
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
import xyz.mcxross.formation.design.components.SkrCoin
import xyz.mcxross.formation.design.components.Tag
import xyz.mcxross.formation.design.components.TopBar
import xyz.mcxross.formation.design.effects.AuroraBackdrop
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Panel
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
    background = { AuroraBackdrop(intensity = 0.4f, colors = listOf(c.reward, c.rewardDeep, c.reward)) },
    topBar = {
      TopBar(title = "Rewards", onBack = { graph.navigator.pop() })
    },
  ) {
    LazyColumn(
      Modifier.fillMaxSize(),
      contentPadding = PaddingValues(horizontal = Space.gutter, vertical = Space.m),
    ) {
      item {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
          SkrCoin(72.dp, spin = true)
          Spacer(Modifier.height(Space.l))
          SkrAmount(
            Skr(waiting.sumOf { it.amount.units }).format(2),
            style = Theme.type.numeralLarge,
            coin = false,
          )
          Text("ready to claim", style = Theme.type.subhead, color = c.contentSecondary)
          Spacer(Modifier.height(Space.l))
          if (graph.ledger.mode == LedgerMode.SIMULATED) {
            Notice(
              "Simulated rewards: they live on this phone while Formation is being built.",
              tone = Tone.Warning,
            )
            Spacer(Modifier.height(Space.l))
          }
        }
      }
      if (tickets.isEmpty()) {
        item {
          EmptyState(
            "No rewards yet",
            "Join a Formation nearby. When the group unlocks its reward, your share appears here.",
            art = { Icon(Icons.Sparkles, null, tint = c.contentTertiary, size = 48.dp) },
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
  Panel(Modifier.fillMaxWidth(), glow = if (ticket.claimed || ticket.lapsed) null else c.reward) {
    Row(Modifier.padding(Space.l), verticalAlignment = Alignment.CenterVertically) {
      ChallengeGlyph(info)
      Spacer(Modifier.width(Space.m))
      Column(Modifier.weight(1f)) {
        Text(
          if (ticket.index < 0) "${info?.title ?: "Formation"} you hosted"
          else "${info?.title ?: "Formation"} with ${ticket.host}",
          style = Theme.type.headline,
          maxLines = 1,
        )
        SkrAmount(
          ticket.amount.format(2),
          color = if (ticket.claimed) c.contentSecondary else c.reward,
          unitColor = c.contentSecondary,
        )
        if (ticket.claimed) {
          Spacer(Modifier.height(Space.xxs))
          Text(
            "In ${shortAddress(ticket.claimedTo!!)}",
            style = Theme.type.caption,
            color = c.contentTertiary,
          )
        }
      }
      if (ticket.claimed) Tag("Claimed", tone = Tone.Positive, icon = Icons.Check)
      else if (ticket.lapsed) Tag("Expired")
      else if (!ticket.unlocked) Tag("Awaiting unlock", icon = Icons.Clock)
      else
        Button(
          "Claim",
          onClaim,
          style = ButtonStyle.Reward,
          size = ButtonSize.Small,
          fillWidth = false,
        )
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
      Modifier.fillMaxWidth().padding(horizontal = Space.xxl),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Spacer(Modifier.height(Space.m))
      SkrCoin(56.dp, spin = busy)
      Spacer(Modifier.height(Space.l))
      Text(
        if (done != null) "Claimed" else "Claim your reward",
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
          done != null -> "It's in ${shortAddress(done!!)}. Welcome to Solana."
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
