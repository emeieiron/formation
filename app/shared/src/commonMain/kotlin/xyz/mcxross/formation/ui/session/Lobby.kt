package xyz.mcxross.formation.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.BottomActions
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.components.ButtonSize
import xyz.mcxross.formation.design.components.ButtonStyle
import xyz.mcxross.formation.design.components.IconButton
import xyz.mcxross.formation.design.components.IconButtonStyle
import xyz.mcxross.formation.design.components.LiveryRule
import xyz.mcxross.formation.design.components.LocalToaster
import xyz.mcxross.formation.design.components.ModalSheet
import xyz.mcxross.formation.design.components.Overline
import xyz.mcxross.formation.design.components.QrCode
import xyz.mcxross.formation.design.components.SheetActions
import xyz.mcxross.formation.design.components.SheetHeader
import xyz.mcxross.formation.design.components.SkrAmount
import xyz.mcxross.formation.design.components.Tag
import xyz.mcxross.formation.design.components.TextField
import xyz.mcxross.formation.design.components.TopBar
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Panel
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.foundation.pressable
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.design.tokens.Tone
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.resources.Res
import xyz.mcxross.formation.resources.a11y_enlarge_qr
import xyz.mcxross.formation.resources.a11y_locked_reward
import xyz.mcxross.formation.resources.a11y_occupancy
import xyz.mcxross.formation.resources.action_begin
import xyz.mcxross.formation.resources.action_open_network
import xyz.mcxross.formation.resources.action_save
import xyz.mcxross.formation.resources.action_share
import xyz.mcxross.formation.resources.headline_scan_to_join
import xyz.mcxross.formation.resources.label_code
import xyz.mcxross.formation.resources.label_emulator
import xyz.mcxross.formation.resources.label_name
import xyz.mcxross.formation.resources.label_profile
import xyz.mcxross.formation.resources.state_connect_wifi
import xyz.mcxross.formation.resources.state_group_ready
import xyz.mcxross.formation.resources.state_waiting
import xyz.mcxross.formation.resources.state_waiting_host
import xyz.mcxross.formation.session.SessionSnapshot
import xyz.mcxross.formation.state.ActiveSession
import xyz.mcxross.formation.state.ChallengeCatalog
import xyz.mcxross.formation.state.Profile
import xyz.mcxross.formation.ui.LocalGraph
import xyz.mcxross.formation.ui.components.AssemblyRoster
import xyz.mcxross.formation.ui.components.ChallengeGlyph
import xyz.mcxross.formation.ui.components.MwaWallets
import xyz.mcxross.formation.ui.components.challengeInfo
import xyz.mcxross.formation.ui.components.rememberWalletInstalled
import xyz.mcxross.formation.ui.components.shortAddress
import xyz.mcxross.formation.ui.screens.HowToPlay
import xyz.mcxross.formation.ui.screens.LightPicker

@Composable
internal fun Lobby(
  session: ActiveSession,
  snapshot: SessionSnapshot,
  me: PlayerId,
  onLeave: () -> Unit,
) {
  val c = Theme.colors
  val graph = LocalGraph.current
  val o = snapshot.formation.opportunity
  val info = challengeInfo(o.challenge)
  val missing = o.players - snapshot.players.size
  val advertising by
    session.advertising.collectAsState(xyz.mcxross.formation.link.DiscoveryStatus.Searching)
  var editing by remember { mutableStateOf(false) }
  if (editing) ProfileSheet(onDismiss = { editing = false })
  Column(Modifier.fillMaxSize()) {
    TopBar(
      title = "Formation",
      onBack = onLeave,
      backIcon = Icons.Close,
      actions = {
        IconButton(
          Icons.User,
          stringResource(Res.string.label_profile),
          { editing = true },
          style = IconButtonStyle.Ghost,
        )
        session.joinLink?.let { link ->
          IconButton(
            Icons.Share,
            stringResource(Res.string.action_share),
            { graph.platform.external.share(link) },
            style = IconButtonStyle.Ghost,
          )
        }
      },
    )
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      if (session.isHost && advertising is xyz.mcxross.formation.link.DiscoveryStatus.Failed) {
        xyz.mcxross.formation.design.components.Notice(
          "Nearby advertising is unavailable. Invite others with this session's QR code.",
          Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
          tone = Tone.Warning,
        )
      }
      Row(
        Modifier.padding(horizontal = Space.gutter),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        ChallengeGlyph(info, size = 40.dp)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
          Text(info?.title ?: o.challenge.value, style = Theme.type.headline)
          Text(
            o.title ?: info?.tagline ?: "",
            style = Theme.type.footnote,
            color = c.contentSecondary,
          )
          Spacer(Modifier.height(Space.xs))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              Icons.Lock,
              stringResource(Res.string.a11y_locked_reward),
              size = 14.dp,
              tint = c.contentSecondary,
            )
            Spacer(Modifier.width(Space.s))
            SkrAmount(
              o.reward.format(0),
              style = Theme.type.footnote,
              color = c.reward,
              coin = false,
            )
          }
        }
      }
      Spacer(Modifier.height(Space.xl))
      LiveryRule(Modifier.padding(horizontal = Space.gutter))
      Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.xl)) {
        val occupancy = stringResource(Res.string.a11y_occupancy, snapshot.players.size, o.players)
        val ready = stringResource(Res.string.state_group_ready)
        val assembled = missing <= 0 && snapshot.players.all { it.connected }
        Row(
          Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription =
              listOfNotNull(occupancy, ready.takeIf { assembled }).joinToString(", ")
          },
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Users, null, tint = c.accent, size = 26.dp)
          Spacer(Modifier.width(Space.m))
          Text(
            "${snapshot.players.size}/${o.players}",
            Modifier.weight(1f),
            style = Theme.type.numeralLarge,
          )
          if (assembled) Tag(ready, tone = Tone.Positive, icon = Icons.Check)
        }
        Spacer(Modifier.height(Space.xl))
        AssemblyRoster(snapshot.players, o.players, me, Modifier.fillMaxWidth())
      }
      if (session.isHost) JoinPanel(session, snapshot) else GuestPanel(snapshot, me)
      ChallengeCatalog[o.challenge]?.let { challenge ->
        Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.xl)) {
          Overline("The goal")
          Spacer(Modifier.height(Space.xs))
          Text(challenge.goal(o.players, o.difficulty), style = Theme.type.bodyStrong)
          Spacer(Modifier.height(Space.l))
          HowToPlay(challenge.info)
        }
      }
    }
    BottomActions {
      SensorStatus(session, Modifier.fillMaxWidth())
      if (session.isHost) {
        Button(
          stringResource(Res.string.action_begin),
          { session.begin() },
          style = ButtonStyle.Primary,
          enabled = missing <= 0 && snapshot.players.all { it.connected && it.sensorReady },
          trailingIcon = if (missing <= 0) Icons.ArrowRight else null,
        )
      } else {
        Box(
          Modifier.fillMaxWidth().heightIn(min = 56.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            stringResource(
              if (missing > 0) Res.string.state_waiting else Res.string.state_waiting_host
            ),
            style = Theme.type.subheadStrong,
            color = c.contentSecondary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
          )
        }
      }
    }
  }
}

@Composable
private fun JoinPanel(session: ActiveSession, snapshot: SessionSnapshot) {
  val c = Theme.colors
  val graph = LocalGraph.current
  var bigQr by remember { mutableStateOf(false) }
  var hotspot by remember { mutableStateOf(false) }
  val address by session.address.collectAsState()
  val network by session.network.collectAsState()
  val link = address?.let { session.joinLink }
  Panel(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
    Column(Modifier.padding(Space.l)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (link != null) {
          Box(
            Modifier.size(112.dp)
              .clip(Shapes.control)
              .pressable(
                { bigQr = true },
                shape = Shapes.control,
                onClickLabel = stringResource(Res.string.a11y_enlarge_qr),
              )
              .background(Color.White)
              .padding(10.dp),
            contentAlignment = Alignment.Center,
          ) {
            QrCode(link, Modifier.fillMaxSize(), color = Color(0xFF06070A))
          }
          Spacer(Modifier.width(Space.l))
        }
        Column(Modifier.weight(1f)) {
          Overline(stringResource(Res.string.label_code))
          Text(
            snapshot.formation.code,
            style = Theme.type.numeralLarge.copy(letterSpacing = Theme.type.code.letterSpacing),
            color = c.content,
          )
        }
      }
      Spacer(Modifier.height(Space.m))
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
      ) {
        Icon(Icons.Wifi, null, tint = c.contentSecondary, size = 16.dp)
        Text(
          when {
            graph.platform.device.emulator -> stringResource(Res.string.label_emulator)
            network != null -> network!!.ssid
            address != null -> "Wi-Fi"
            else -> stringResource(Res.string.state_connect_wifi)
          },
          style = Theme.type.footnote,
          color = c.contentSecondary,
        )
      }
    }
  }
  if (graph.platform.hotspot != null) {
    Row(Modifier.padding(horizontal = Space.gutter, vertical = Space.m)) {
      Button(
        network?.ssid ?: stringResource(Res.string.action_open_network),
        { hotspot = true },
        style = ButtonStyle.Ghost,
        size = ButtonSize.Medium,
        leadingIcon = Icons.Broadcast,
      )
    }
  }
  if (bigQr && link != null) {
    ModalSheet(onDismiss = { bigQr = false }) {
      SheetHeader(stringResource(Res.string.headline_scan_to_join))
      Box(
        Modifier.fillMaxWidth()
          .padding(horizontal = Space.xxl)
          .aspectRatio(1f)
          .clip(Shapes.panel)
          .background(Color.White)
          .padding(Space.xl)
      ) {
        QrCode(link, Modifier.fillMaxSize(), color = Color(0xFF06070A))
      }
      Spacer(Modifier.height(Space.l))
      Text(
        snapshot.formation.code,
        style = Theme.type.numeralLarge,
        color = c.content,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
      )
    }
  }
  if (hotspot) SeekerNetworkSheet(session, onDismiss = { hotspot = false })
}

@Composable
private fun SeekerNetworkSheet(session: ActiveSession, onDismiss: () -> Unit) {
  val c = Theme.colors
  val graph = LocalGraph.current
  val toaster = LocalToaster.current
  val scope = rememberCoroutineScope()
  val info by session.network.collectAsState()
  var starting by remember { mutableStateOf(false) }
  ModalSheet(onDismiss) {
    SheetHeader(
      "Seeker network",
      subtitle =
        "Your Seeker becomes a Wi-Fi network just for this Formation. Others join it by scanning, then find the Formation under Nearby. Rewards settle once internet is available.",
    )
    val current = info
    if (current == null) {
      Column(Modifier.padding(horizontal = Space.xxl)) {
        Button(
          if (graph.platform.hotspot?.permitted() == true) "Open the network"
          else "Allow and open network",
          {
            starting = true
            scope.launch {
              graph.openSeekerNetwork().onFailure {
                toaster.show(it.message ?: "This phone couldn't open a network", Tone.Warning)
              }
              starting = false
            }
          },
          loading = starting,
          leadingIcon = Icons.Broadcast,
        )
      }
    } else {
      val wifi =
        "WIFI:T:${if (current.passphrase == null) "nopass" else "WPA"};S:${current.ssid};${current.passphrase?.let { "P:$it;" } ?: ""};"
      Box(
        Modifier.fillMaxWidth()
          .padding(horizontal = Space.x4l)
          .aspectRatio(1f)
          .clip(Shapes.panel)
          .background(Color.White)
          .padding(Space.l)
      ) {
        QrCode(wifi, Modifier.fillMaxSize(), color = Color(0xFF06070A))
      }
      Spacer(Modifier.height(Space.l))
      Column(Modifier.fillMaxWidth().padding(horizontal = Space.xxl)) {
        Text("Network  ${current.ssid}", style = Theme.type.bodyStrong)
        current.passphrase?.let {
          Text("Password  $it", style = Theme.type.body, color = c.contentSecondary)
        }
        Spacer(Modifier.height(Space.s))
        Text(
          "Once they're on it, the Formation appears under Nearby, or they scan the join code.",
          style = Theme.type.footnote,
          color = c.contentSecondary,
        )
      }
    }
  }
}

@Composable
private fun ProfileSheet(onDismiss: () -> Unit) {
  val graph = LocalGraph.current
  val current = graph.identity.profile.value ?: return
  var name by remember { mutableStateOf(current.name) }
  var light by remember { mutableStateOf(current.light) }
  ModalSheet(onDismiss) {
    SheetHeader(stringResource(Res.string.label_profile))
    Column(
      Modifier.weight(1f, fill = false)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = Space.xxl)
    ) {
      TextField(name, { name = it }, label = stringResource(Res.string.label_name), maxLength = 20)
      Spacer(Modifier.height(Space.l))
      LightPicker(light, onPick = { light = it })
    }
    SheetActions {
      Button(
        stringResource(Res.string.action_save),
        {
          graph.updateProfile(Profile(name.trim(), light))
          onDismiss()
        },
        enabled = name.isNotBlank(),
      )
    }
  }
}

@Composable
private fun GuestPanel(snapshot: SessionSnapshot, me: PlayerId) {
  val c = Theme.colors
  val graph = LocalGraph.current
  val toaster = LocalToaster.current
  val scope = rememberCoroutineScope()
  val o = snapshot.formation.opportunity
  val wallet = snapshot.player(me)?.wallet
  val installed = rememberWalletInstalled(graph.platform)
  var connecting by remember { mutableStateOf(false) }
  val suggested = MwaWallets.first()
  Panel(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
    Column(Modifier.padding(Space.l)) {
      Overline("Your share if you unlock it")
      Spacer(Modifier.height(Space.xs))
      SkrAmount(
        o.split().helper.format(2),
        style = Theme.type.numeral,
        color = c.reward,
        unitColor = c.contentSecondary,
      )
      Text("No wallet needed to play", style = Theme.type.footnote, color = c.contentSecondary)
      Spacer(Modifier.height(Space.m))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          Icons.Wallet,
          null,
          tint = if (wallet != null) c.positive else c.contentSecondary,
          size = 18.dp,
        )
        Spacer(Modifier.width(Space.s))
        Text(
          when {
            wallet != null -> "Lands in ${shortAddress(wallet)} when it unlocks"
            installed -> "Connect a wallet and it lands there"
            else -> "Get ${suggested.name} and it lands there"
          },
          style = Theme.type.footnote,
          color = c.contentSecondary,
          modifier = Modifier.weight(1f),
        )
      }
      Spacer(Modifier.height(Space.m))
      Row {
        when {
          installed ->
            Button(
              if (wallet != null) "Change" else "Connect",
              {
                connecting = true
                scope.launch {
                  graph.connectWallet()?.let { toaster.show(it, Tone.Warning) }
                  connecting = false
                }
              },
              style = if (wallet != null) ButtonStyle.Ghost else ButtonStyle.Reward,
              size = ButtonSize.Small,
              fillWidth = false,
              loading = connecting,
            )
          else ->
            Button(
              "Get ${suggested.name}",
              { graph.platform.external.openUrl(suggested.storeUrl) },
              style = ButtonStyle.Secondary,
              size = ButtonSize.Small,
              fillWidth = false,
            )
        }
      }
    }
  }
}
