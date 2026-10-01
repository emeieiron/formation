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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.BottomActions
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.components.ButtonSize
import xyz.mcxross.formation.design.components.ButtonStyle
import xyz.mcxross.formation.design.components.IconButton
import xyz.mcxross.formation.design.components.IconButtonStyle
import xyz.mcxross.formation.design.components.LocalToaster
import xyz.mcxross.formation.design.components.ModalSheet
import xyz.mcxross.formation.design.components.Overline
import xyz.mcxross.formation.design.components.PlayerLight
import xyz.mcxross.formation.design.components.QrCode
import xyz.mcxross.formation.design.components.SheetActions
import xyz.mcxross.formation.design.components.SheetHeader
import xyz.mcxross.formation.design.components.SkrAmount
import xyz.mcxross.formation.design.components.Spinner
import xyz.mcxross.formation.design.components.TextField
import xyz.mcxross.formation.design.components.TopBar
import xyz.mcxross.formation.design.effects.FormationRing
import xyz.mcxross.formation.design.effects.RingMember
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Panel
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.foundation.pressable
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Colors
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.design.tokens.Tone
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.Player
import xyz.mcxross.formation.session.SessionSnapshot
import xyz.mcxross.formation.state.ActiveSession
import xyz.mcxross.formation.state.ChallengeCatalog
import xyz.mcxross.formation.state.Profile
import xyz.mcxross.formation.ui.LocalGraph
import xyz.mcxross.formation.ui.components.ChallengeGlyph
import xyz.mcxross.formation.ui.components.MwaWallets
import xyz.mcxross.formation.ui.components.challengeInfo
import xyz.mcxross.formation.ui.components.possessive
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
  var editing by remember { mutableStateOf(false) }
  if (editing) ProfileSheet(onDismiss = { editing = false })
  Column(Modifier.fillMaxSize()) {
    TopBar(
      title =
        if (session.isHost) "Your Formation"
        else "${possessive(snapshot.formation.host)} Formation",
      onBack = onLeave,
      backIcon = Icons.Close,
      actions = {
        session.joinLink?.let { link ->
          IconButton(
            Icons.Share,
            "Share",
            { graph.platform.external.share("Join my Formation: $link") },
            style = IconButtonStyle.Ghost,
          )
        }
      },
    )
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
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
        }
        Icon(Icons.Lock, null, tint = c.reward, size = 18.dp)
        Spacer(Modifier.width(Space.xs))
        SkrAmount(o.reward.format(0), color = c.reward, coin = false)
      }
      Spacer(Modifier.height(Space.l))
      FormationRing(
        members = members(snapshot, me, c),
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(horizontal = Space.l),
        lightSize = if (o.players > 8) 40.dp else 52.dp,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            "${snapshot.players.size}/${o.players}",
            style = Theme.type.numeralLarge,
            color = c.content,
          )
          Text(
            if (missing > 0) "joined" else "ready to go",
            style = Theme.type.footnote,
            color = c.contentSecondary,
          )
        }
      }
      snapshot.player(me)?.let { mine -> YouRow(mine, onEdit = { editing = true }) }
      Spacer(Modifier.height(Space.l))
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
      if (session.isHost) {
        Button(
          if (missing > 0) "Waiting for $missing more" else "Begin",
          { session.begin() },
          style = ButtonStyle.Primary,
          enabled = missing <= 0,
          trailingIcon = if (missing <= 0) Icons.ArrowRight else null,
        )
      } else {
        Row(
          Modifier.fillMaxWidth().height(56.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center,
        ) {
          Spinner(16.dp, color = c.contentSecondary)
          Spacer(Modifier.width(Space.s))
          Text(
            if (missing > 0) "Waiting for $missing more ${if (missing == 1) "person" else "people"}"
            else "Waiting for ${snapshot.formation.host} to begin",
            style = Theme.type.subheadStrong,
            color = c.contentSecondary,
          )
        }
      }
    }
  }
}

internal fun members(snapshot: SessionSnapshot, me: PlayerId?, colors: Colors): List<RingMember?> {
  val filled = snapshot.players.map { it.toRing(me, colors) }
  return filled +
    List((snapshot.formation.opportunity.players - filled.size).coerceAtLeast(0)) { null }
}

internal fun Player.toRing(me: PlayerId?, colors: Colors) =
  RingMember(
    name,
    colors.light(light),
    seeker = seeker,
    connected = connected,
    ready = ready,
    me = id == me,
  )

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
    Row(Modifier.padding(Space.l), verticalAlignment = Alignment.CenterVertically) {
      if (link != null) {
        Box(
          Modifier.size(112.dp)
            .clip(Shapes.control)
            .pressable({ bigQr = true }, shape = Shapes.control)
            .background(Color.White)
            .padding(10.dp),
          contentAlignment = Alignment.Center,
        ) {
          QrCode(link, Modifier.fillMaxSize(), color = Color(0xFF06070A))
        }
        Spacer(Modifier.width(Space.l))
      }
      Column(Modifier.weight(1f)) {
        Overline("Join code")
        Text(
          snapshot.formation.code,
          style = Theme.type.numeralLarge.copy(letterSpacing = Theme.type.code.letterSpacing),
          color = c.content,
        )
        Spacer(Modifier.height(Space.xs))
        Text(
          when {
            graph.platform.device.emulator ->
              "Emulator: run scripts/emulators.sh link so the others can see this Formation."
            network != null ->
              "People on your Seeker network, ${network!!.ssid}, see it under Nearby. Or scan the code."
            address != null -> "People on the same Wi-Fi see it under Nearby. Or scan the code."
            else -> "Connect to Wi-Fi, or open a Seeker network, so people can join."
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
        network?.let { "Seeker network: ${it.ssid}" } ?: "No Wi-Fi? Open a Seeker network",
        { hotspot = true },
        style = ButtonStyle.Ghost,
        size = ButtonSize.Medium,
        leadingIcon = Icons.Broadcast,
      )
    }
  }
  if (bigQr && link != null) {
    ModalSheet(onDismiss = { bigQr = false }) {
      SheetHeader("Scan to join", subtitle = "Point any phone's camera at this code.")
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
        "Your Seeker becomes a Wi-Fi network just for this Formation. Others join it by scanning, then find the Formation under Nearby.",
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
private fun YouRow(mine: Player, onEdit: () -> Unit) {
  val c = Theme.colors
  Row(
    Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      Modifier.clip(Shapes.control)
        .pressable(onEdit, shape = Shapes.control)
        .padding(horizontal = Space.m, vertical = Space.s),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PlayerLight(mine.name, c.light(mine.light), size = 24.dp)
      Spacer(Modifier.width(Space.s))
      Text("You're ${mine.name}", style = Theme.type.subheadStrong, color = c.content)
      Spacer(Modifier.width(Space.s))
      Text("Edit", style = Theme.type.subheadStrong, color = c.accent)
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
    SheetHeader("How the group sees you", subtitle = "Changes show on every phone in the lobby.")
    Column(Modifier.padding(horizontal = Space.xxl)) {
      TextField(name, { name = it }, placeholder = "Your name", maxLength = 20)
      Spacer(Modifier.height(Space.l))
      LightPicker(light, onPick = { light = it })
    }
    SheetActions {
      Button(
        "Save",
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
  Panel(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), glow = c.reward) {
    Column(Modifier.padding(Space.l)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Overline("Your share if you unlock it")
          Spacer(Modifier.height(Space.xs))
          SkrAmount(
            o.split().helper.format(2),
            style = Theme.type.numeral,
            color = c.reward,
            unitColor = c.reward,
          )
        }
        Text(
          "No wallet needed\nto play",
          style = Theme.type.footnote,
          color = c.contentSecondary,
          textAlign = TextAlign.End,
        )
      }
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
        Spacer(Modifier.width(Space.s))
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
