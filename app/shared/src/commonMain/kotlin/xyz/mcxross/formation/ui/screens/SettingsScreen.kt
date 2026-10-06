package xyz.mcxross.formation.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import xyz.mcxross.formation.resources.Res
import xyz.mcxross.formation.resources.label_name
import xyz.mcxross.formation.resources.label_profile
import xyz.mcxross.formation.resources.label_sound
import xyz.mcxross.formation.resources.label_sound_effects
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.LiveryRule
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.components.TextButton
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
import xyz.mcxross.formation.state.HardwareCheck
import xyz.mcxross.formation.state.KEY_DEV_SEEKER_HARDWARE
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
  val hardware by graph.hardware.local.collectAsState()
  val soundEnabled by graph.sounds.enabled.collectAsState()
  val claimState by graph.identity.claims.status.collectAsState()
  var name by remember { mutableStateOf(profile?.name ?: "") }
  var light by remember { mutableStateOf(profile?.light ?: 0) }

  fun save() {
    if (name.isNotBlank()) graph.updateProfile(Profile(name.trim(), light))
  }

  Page(
    topBar = {
      TopBar(
        title = stringResource(Res.string.label_profile),
        onBack = {
          save()
          graph.navigator.pop()
        },
      )
    }
  ) {
    LiveryRule(Modifier.padding(horizontal = Space.gutter))
    Column(Modifier.verticalScroll(rememberScrollState())) {
      Box(
        Modifier.fillMaxWidth().padding(vertical = Space.l),
        contentAlignment = Alignment.Center,
      ) {
        PlayerLight(
          name.ifBlank { "?" },
          c.light(light),
          size = Sizes.lightXl,
          seeker = seeker != null,
        )
      }
      Column(Modifier.padding(horizontal = Space.gutter)) {
        TextField(name, { name = it }, label = stringResource(Res.string.label_name), maxLength = 20)
        Spacer(Modifier.height(Space.l))
        LightPicker(
          light,
          onPick = {
            light = it
            save()
          },
        )
      }

      SectionHeader(stringResource(Res.string.label_sound))
      val soundLabel = stringResource(Res.string.label_sound_effects)
      Panel(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        SettingRow(
          soundLabel,
          icon = Icons.Sound,
          trailing = {
            Toggle(
              soundEnabled,
              graph.sounds::setEnabled,
              modifier = Modifier.semantics { contentDescription = soundLabel },
            )
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
                s != null -> "Wallet linked"
                status == SeekerStatus.Checking -> "Checking…"
                graph.platform.device.seeker -> "Seeker not linked"
                else -> "Not a Seeker"
              },
            detail =
              when {
                s?.simulated == true ->
                  "Testing only. Hosts with this phone's claim key instead of a Seeker Genesis Token."
                s != null -> "${shortAddress(s.wallet)} holds Genesis Token ${shortAddress(s.sgt ?: "")}. This phone hosts for it."
                graph.platform.device.seeker -> "Link the wallet that holds its Genesis Token to host Formations."
                else -> "This phone joins Formations that Seeker owners start."
              },
            icon = Icons.Seeker,
          )
          if (s?.simulated != true) {
            Hairline()
            Row(Modifier.padding(Space.l), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
              when {
                s != null -> {
                  Button("Check again", { scope.launch { graph.seeker.autoVerify() } }, style = ButtonStyle.Secondary,
                    size = ButtonSize.Medium, fillWidth = false, loading = status == SeekerStatus.Checking)
                  Button("Unlink", { graph.seeker.forget() }, style = ButtonStyle.Ghost, size = ButtonSize.Medium, fillWidth = false)
                }
                graph.platform.device.seeker ->
                  Button("Link this Seeker", { scope.launch { graph.seeker.link() } }, style = ButtonStyle.Reward,
                    size = ButtonSize.Medium, loading = status == SeekerStatus.Checking)
                else -> TextButton("Have a Seeker? Link its wallet", { scope.launch { graph.seeker.link() } })
              }
            }
          }
        }
      }

      SectionHeader("Rewards")
      Panel(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column {
          SettingRow(
            "Ledger",
            detail = "Formation vault on ${graph.platform.config.cluster}.",
            icon = Icons.Coin,
          )
          Hairline()
          SettingRow("Reward recovery", detail = if ((claimState as? xyz.mcxross.formation.state.recovery.ClaimKeyState.Ready)?.protectedOnDevice == true)
            "Automatic protection on this phone" else "Recovery options", icon = Icons.Lock, trailing = {
            Button("Open", { graph.navigator.push(Screen.Recovery) }, style = ButtonStyle.Secondary,
              size = ButtonSize.Small, fillWidth = false)
          })
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

      DiagnosticsSettings()

      if (graph.platform.config.developer) {
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
            SettingRow(
              title = when (hardware) {
                HardwareCheck.Proven -> "Seeker hardware attested"
                HardwareCheck.Checking -> "Checking this phone…"
                is HardwareCheck.Failed -> "Seeker hardware not attested"
                HardwareCheck.Unknown -> "Seeker hardware attestation"
              },
              detail = when (val check = hardware) {
                HardwareCheck.Proven -> "Secure hardware shows a Seeker with a locked bootloader running this app."
                is HardwareCheck.Failed -> check.message
                else -> "For a future Seeker present badge. Hosting never depends on it."
              },
              icon = Icons.Lock,
              trailing = {
                Button("Check", { scope.launch { graph.hardware.proveThisPhone() } }, style = ButtonStyle.Secondary,
                  size = ButtonSize.Small, fillWidth = false, loading = hardware == HardwareCheck.Checking)
              },
            )
            Hairline()
            var seekerHardware by remember { mutableStateOf(graph.platform.store.get(KEY_DEV_SEEKER_HARDWARE) == "true") }
            SettingRow(
              "Act as Seeker hardware",
              detail = "Shows a Seeker's onboarding on this phone." +
                if (seekerHardware != graph.platform.device.seeker) " Restart Formation to apply." else "",
              icon = Icons.Phone,
              trailing = {
                Toggle(seekerHardware, { on ->
                  seekerHardware = on
                  graph.platform.store.put(KEY_DEV_SEEKER_HARDWARE, if (on) "true" else null)
                  toaster.show("Restart Formation to apply")
                })
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
