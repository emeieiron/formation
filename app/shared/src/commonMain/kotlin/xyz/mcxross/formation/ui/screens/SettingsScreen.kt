package xyz.mcxross.formation.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.components.ButtonSize
import xyz.mcxross.formation.design.components.ButtonStyle
import xyz.mcxross.formation.design.components.LocalToaster
import xyz.mcxross.formation.design.components.NavigationBarSpacer
import xyz.mcxross.formation.design.components.Page
import xyz.mcxross.formation.design.components.PlayerLight
import xyz.mcxross.formation.design.components.SectionHeader
import xyz.mcxross.formation.design.components.SettingRow
import xyz.mcxross.formation.design.components.TextField
import xyz.mcxross.formation.design.components.Toggle
import xyz.mcxross.formation.design.components.TopBar
import xyz.mcxross.formation.design.foundation.Hairline
import xyz.mcxross.formation.design.foundation.Panel
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Sizes
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.link.EmulatorBridge
import xyz.mcxross.formation.state.LedgerMode
import xyz.mcxross.formation.state.Profile
import xyz.mcxross.formation.state.SeekerStatus
import xyz.mcxross.formation.ui.LocalGraph
import xyz.mcxross.formation.ui.components.shortAddress
import xyz.mcxross.formation.ui.nav.Screen

@Composable
fun SettingsScreen() {
  val graph = LocalGraph.current
  val c = Theme.colors
  val toaster = LocalToaster.current
  val scope = rememberCoroutineScope()
  val profile by graph.identity.profile.collectAsState()
  val seeker by graph.seeker.identity.collectAsState()
  val status by graph.seeker.status.collectAsState()
  var name by remember { mutableStateOf(profile?.name ?: "") }
  var light by remember { mutableStateOf(profile?.light ?: 0) }

  fun save() {
    if (name.isNotBlank()) graph.updateProfile(Profile(name.trim(), light))
  }

  Page(
    topBar = {
      TopBar(
        title = "You",
        onBack = {
          save()
          graph.navigator.pop()
        },
      )
    }
  ) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
      Box(
        Modifier.fillMaxWidth().padding(vertical = Space.l),
        contentAlignment = Alignment.Center,
      ) {
        PlayerLight(
          name.ifBlank { "?" },
          c.light(light),
          size = Sizes.lightXl,
          pulse = true,
          seeker = seeker != null,
        )
      }
      Column(Modifier.padding(horizontal = Space.gutter)) {
        TextField(name, { name = it }, label = "Name", maxLength = 20)
        Spacer(Modifier.height(Space.l))
        LightPicker(
          light,
          onPick = {
            light = it
            save()
          },
        )
      }

      SectionHeader("Seeker")
      Panel(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column {
          val s = seeker
          SettingRow(
            title =
              when {
                s?.simulated == true -> "Pretend Seeker"
                s != null -> "Seeker verified"
                status == SeekerStatus.Checking -> "Checking…"
                graph.platform.device.seeker -> "Seeker not linked"
                else -> "Not a Seeker"
              },
            detail =
              when {
                s?.simulated == true ->
                  "Testing only. Hosts with this phone's claim key instead of a Seeker Genesis Token."
                s != null -> "Wallet ${shortAddress(s.wallet)} · SGT ${shortAddress(s.sgt ?: "")}"
                graph.platform.device.seeker ->
                  "Approve Formation in your Seed Vault to host Formations."
                else -> "This phone can join any Formation. Hosting needs a Seeker."
              },
            icon = Icons.Seeker,
          )
          if (s?.simulated != true && (graph.platform.device.seeker || s != null)) {
            Hairline()
            Box(Modifier.padding(Space.l)) {
              Button(
                if (s == null) "Link this Seeker" else "Check again",
                {
                  scope.launch { if (s == null) graph.seeker.link() else graph.seeker.autoVerify() }
                },
                style = if (s == null) ButtonStyle.Reward else ButtonStyle.Secondary,
                size = ButtonSize.Medium,
                loading = status == SeekerStatus.Checking,
              )
            }
          }
        }
      }

      SectionHeader("Rewards")
      Panel(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column {
          SettingRow(
            "Ledger",
            detail =
              "${graph.ledger.mode.label}. " +
                if (graph.ledger.mode == LedgerMode.SIMULATED) "Nothing touches a chain."
                else "Formation vault on ${graph.platform.config.cluster}.",
            icon = Icons.Coin,
          )
          Hairline()
          SettingRow("Claim key", detail = graph.identity.claimAddress, icon = Icons.Lock)
          Hairline()
          SettingRow(
            "Your rewards",
            icon = Icons.Sparkles,
            trailing = {
              Button(
                "Open",
                { graph.navigator.push(Screen.Rewards) },
                style = ButtonStyle.Secondary,
                size = ButtonSize.Small,
                fillWidth = false,
              )
            },
          )
        }
      }

      if (graph.platform.config.debug) {
        SectionHeader("Developer")
        Panel(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
          Column {
            SettingRow(
              "Pretend to be a Seeker",
              detail =
                "Testing only. Hosts Formations without a Seeker Genesis Token, signing with this phone's claim key.",
              icon = Icons.Seeker,
              trailing = {
                Toggle(
                  seeker?.simulated == true,
                  { on -> graph.seeker.pretend(on, graph.identity.claimAddress) },
                )
              },
            )
            Hairline()
            var solana by remember { mutableStateOf(graph.ledgerChoice == LedgerMode.SOLANA) }
            SettingRow(
              "Solana ledger",
              detail =
                "Use the vault program on ${graph.platform.config.cluster} (${graph.platform.config.rpcUrl})." +
                  if (solana != (graph.ledger.mode == LedgerMode.SOLANA))
                    " Restart Formation to switch."
                  else "",
              icon = Icons.Coin,
              trailing = {
                Toggle(
                  solana,
                  { on ->
                    solana = on
                    graph.chooseLedger(if (on) LedgerMode.SOLANA else LedgerMode.SIMULATED)
                    toaster.show("Restart Formation to switch ledgers")
                  },
                )
              },
            )
            Hairline()
            SettingRow(
              "Emulator bridge",
              detail =
                if (graph.platform.device.emulator)
                  "Probing ${EmulatorBridge.candidates().first()} to …${EmulatorBridge.candidates().last().port}. Run scripts/emulators.sh link on the host."
                else "Only used on emulators.",
              icon = Icons.Wifi,
            )
            Hairline()
            SettingRow(
              "Device",
              detail =
                "${graph.platform.device.model} · Formation ${graph.platform.config.version}",
              icon = Icons.Phone,
            )
          }
        }
      }
      Spacer(Modifier.height(Space.x3l))
      Text(
        "The Seeker creates the opportunity. The group creates the unlock.",
        style = Theme.type.footnote,
        color = c.contentTertiary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      )
      Spacer(Modifier.height(Space.xl))
      NavigationBarSpacer()
    }
  }
}
