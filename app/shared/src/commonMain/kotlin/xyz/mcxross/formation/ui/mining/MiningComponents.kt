package xyz.mcxross.formation.ui.mining

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.components.ButtonStyle
import xyz.mcxross.formation.design.components.TextButton
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.state.mining.MiningAccountState
import xyz.mcxross.formation.state.mining.MiningOperation
import xyz.mcxross.formation.ui.LocalGraph
import xyz.mcxross.formation.ui.components.shortAddress

/** Navigation owns the view model; the app-scoped repository retains financial state. */
@Composable
internal fun <T : ViewModel> ownedViewModel(key: Any, create: () -> T): T {
  val owned =
    remember(key) {
      val model = create()
      ViewModelStore().also { it.put("model", model) } to model
    }
  DisposableEffect(owned) { onDispose { owned.first.clear() } }
  return owned.second
}

@Composable
internal fun MiningWallet(
  state: MiningAccountState,
  connect: () -> Unit,
  request: () -> Unit,
  refresh: () -> Unit,
) {
  val graph = LocalGraph.current
  if (state.wallet == null) {
    Button(
      "Connect wallet",
      connect,
      enabled = !state.busy,
      loading = state.operation == MiningOperation.Connecting,
    )
  } else {
    Text(
      "Wallet ${shortAddress(state.wallet)}",
      style = Theme.type.footnote,
      color = Theme.colors.contentSecondary,
    )
    Text(
      "Balance: ${state.balance?.let { units(it.toULong(), 9) } ?: "—"} test SOL",
      style = Theme.type.body,
    )
    if (state.balance != null && state.balance < 11_000_000) {
      Button(
        "Get test SOL",
        request,
        enabled = !state.busy,
        loading = state.operation == MiningOperation.Funding,
        style = ButtonStyle.Secondary,
      )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
      TextButton("Refresh", refresh, enabled = !state.busy)
      TextButton("Change wallet", connect, enabled = !state.busy)
    }
    if (state.faucetAvailable) {
      SelectionContainer { Text(state.wallet, style = Theme.type.footnote) }
      TextButton("Open faucet", { graph.platform.external.openUrl("https://faucet.solana.com/") })
    }
  }
  MiningFeedback(state)
}

/** Recovery remains reachable in a game and after leaving or restarting. */
@Composable
internal fun MiningRecovery(
  state: MiningAccountState,
  connect: () -> Unit,
  refresh: () -> Unit,
  settle: () -> Unit,
  modifier: Modifier = Modifier,
  title: Boolean = false,
) {
  if (!state.needsAttention) return
  val graph = LocalGraph.current
  val position = state.outstandingPositions.lastOrNull { it.wallet == state.wallet }
  val ownAttention = state.canRecover || state.awaitingDraw || position != null
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
    if (title) Text("Longshot", style = Theme.type.title3)
    when {
      state.canRecover -> {
        Text("Mining proceeds are ready.", style = Theme.type.body)
        if (state.claimableSol > 0u)
          Text("${units(state.claimableSol, 9)} test SOL", style = Theme.type.bodyStrong)
        if (state.claimableOre > 0u)
          Text("${units(state.claimableOre, 11)} test ORE", style = Theme.type.bodyStrong)
        Button(
          "Recover proceeds",
          settle,
          style = ButtonStyle.Secondary,
          enabled = !state.busy,
          loading = state.operation == MiningOperation.Recovering,
        )
      }
      state.awaitingDraw -> Text("Mining is waiting for the draw.", style = Theme.type.body)
      state.pendingSubmission -> Text("Confirming mining…", style = Theme.type.body)
      position != null -> Text("Checking mining proceeds…", style = Theme.type.body)
      else -> {
        val other = state.outstandingPositions.lastOrNull()
        Text(
          "Mining needs attention in wallet ${other?.wallet?.let(::shortAddress).orEmpty()}.",
          style = Theme.type.body,
        )
        Button(
          "Connect wallet",
          connect,
          enabled = !state.busy,
          loading = state.operation == MiningOperation.Connecting,
          style = ButtonStyle.Secondary,
        )
      }
    }
    if (ownAttention) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton("Refresh", refresh, enabled = !state.busy)
        position?.signature?.let { signature ->
          TextButton(
            "View transaction",
            {
              graph.platform.external.openUrl(
                "https://explorer.solana.com/tx/$signature?cluster=devnet"
              )
            },
          )
        }
      }
    }
    MiningFeedback(state)
  }
}

@Composable
private fun MiningFeedback(state: MiningAccountState) {
  val label =
    when (state.operation) {
      MiningOperation.Connecting -> "Connecting wallet…"
      MiningOperation.Funding -> "Requesting test SOL…"
      MiningOperation.Mining -> "Waiting for wallet approval…"
      MiningOperation.Recovering -> "Recovering proceeds…"
      else -> null
    }
  label?.let { Text(it, style = Theme.type.footnote, color = Theme.colors.contentSecondary) }
  state.message?.let { Text(it, style = Theme.type.footnote) }
}

internal fun units(value: ULong, decimals: Int): String {
  val digits = value.toString().padStart(decimals + 1, '0')
  val whole = digits.dropLast(decimals)
  val fraction = digits.takeLast(decimals).trimEnd('0')
  return if (fraction.isEmpty()) whole else "$whole.$fraction"
}
