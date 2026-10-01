package xyz.mcxross.formation.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.components.ButtonSize
import xyz.mcxross.formation.design.components.ButtonStyle
import xyz.mcxross.formation.design.components.LocalToaster
import xyz.mcxross.formation.design.components.NavigationBarSpacer
import xyz.mcxross.formation.design.components.Notice
import xyz.mcxross.formation.design.components.Page
import xyz.mcxross.formation.design.components.PlayerLight
import xyz.mcxross.formation.design.components.SectionHeader
import xyz.mcxross.formation.design.components.SkrAmount
import xyz.mcxross.formation.design.components.SkrCoin
import xyz.mcxross.formation.design.components.Spinner
import xyz.mcxross.formation.design.components.Tag
import xyz.mcxross.formation.design.effects.FormationMark
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Panel
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.foundation.pressable
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.design.tokens.Tone
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.model.Skr
import xyz.mcxross.formation.session.NearbyFormation
import xyz.mcxross.formation.state.Links
import xyz.mcxross.formation.state.SeekerStatus
import xyz.mcxross.formation.ui.LocalGraph
import xyz.mcxross.formation.ui.components.ChallengeGlyph
import xyz.mcxross.formation.ui.components.Slots
import xyz.mcxross.formation.ui.components.TierTag
import xyz.mcxross.formation.ui.components.challengeInfo
import xyz.mcxross.formation.ui.components.possessive
import xyz.mcxross.formation.ui.components.shortAddress
import xyz.mcxross.formation.ui.components.timeLeft
import xyz.mcxross.formation.ui.nav.Screen

@Composable
fun HomeScreen() {
  val graph = LocalGraph.current
  val c = Theme.colors
  val toaster = LocalToaster.current
  val scope = rememberCoroutineScope()
  val profile by graph.identity.profile.collectAsState()
  val seeker by graph.seeker.identity.collectAsState()
  val opportunities by graph.ledger.opportunities.collectAsState()
  val tickets by graph.ledger.tickets.collectAsState()
  val problem by graph.ledger.problem.collectAsState()
  // Scans only while Home is on screen.
  val nearby by
    produceState(emptyList<NearbyFormation>()) { graph.nearby.scan().collect { value = it } }
  var opened by remember { mutableStateOf<Opportunity?>(null) }
  var enteringCode by remember { mutableStateOf(false) }
  val status by graph.seeker.status.collectAsState()
  val pendingWins by graph.pending.pending.collectAsState()

  LaunchedEffect(seeker) { seeker?.let { graph.ledger.refresh(it) } }
  LaunchedEffect(Unit) { graph.seeker.autoVerify() }

  fun join(formation: NearbyFormation) {
    graph.join(formation.address)
    graph.navigator.push(Screen.Session)
  }

  fun scan() = scope.launch {
    val text = graph.platform.external.scanQr() ?: return@launch
    val target = Links.parse(text)
    val address = target?.address
    if (address == null) {
      toaster.show("That QR code isn't a Formation", Tone.Warning)
      return@launch
    }
    graph.join(address)
    graph.navigator.push(Screen.Session)
  }

  Page {
    Row(
      Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.s, top = Space.s),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FormationMark(Modifier.size(width = 40.dp, height = 24.dp))
      Spacer(Modifier.width(Space.s))
      Text("Formation", style = Theme.type.title3, modifier = Modifier.weight(1f))
      profile?.let { p ->
        Box(
          Modifier.pressable(
              { graph.navigator.push(Screen.Settings) },
              shape = Shapes.circle,
              onClickLabel = "Your profile",
            )
            .padding(6.dp)
        ) {
          PlayerLight(p.name, c.light(p.light), size = 36.dp, seeker = seeker != null)
        }
      }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.x4l)) {
      item {
        Column(Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.l)) {
          Text("Hi, ${profile?.name ?: "there"}", style = Theme.type.display)
          Spacer(Modifier.height(Space.xs))
          Text(
            when {
              seeker != null && opportunities.isNotEmpty() ->
                "${opportunities.size} locked ${if (opportunities.size == 1) "reward is" else "rewards are"} waiting on your Seeker."
              seeker != null -> "No locked rewards right now."
              else -> "Join a Formation nearby and help unlock its reward."
            },
            style = Theme.type.body,
            color = c.contentSecondary,
          )
        }
      }

      val unclaimed = tickets.filter { !it.claimed && it.unlocked && !it.lapsed }
      if (unclaimed.isNotEmpty()) {
        item {
          RewardsBanner(
            Skr(unclaimed.sumOf { it.amount.units }),
            unclaimed.size,
            onOpen = { graph.navigator.push(Screen.Rewards) },
          )
        }
      }

      val me = seeker
      if (me != null && pendingWins.isNotEmpty()) {
        items(pendingWins, key = { "pending-" + it.opportunity.id.value }) { win ->
          Notice(
            "Everyone sealed it. It unlocks on chain once this Seeker is online.",
            Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
            tone = Tone.Warning,
            title = "Sealed win · ${win.opportunity.reward.format(0)} SKR",
            action = "Unlock now",
            onAction = {
              scope.launch {
                graph.unlockWin(win).onFailure {
                  toaster.show(it.message ?: "Still offline", Tone.Warning)
                }
              }
            },
          )
        }
      }
      if (me != null) {
        item { SectionHeader(if (me.simulated) "Your Seeker · simulated" else "Your Seeker") }
        item {
          LazyRow(
            contentPadding = PaddingValues(horizontal = Space.gutter),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
          ) {
            items(opportunities, key = { it.id.value }) { o ->
              OpportunityCard(o, onOpen = { opened = o })
            }
          }
        }
        problem?.let {
          item {
            Notice(
              it,
              Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
              tone = Tone.Warning,
            )
          }
        }
      } else {
        item { SeekerStatusRow(status, onRetry = { scope.launch { graph.seeker.link() } }) }
      }

      item { SectionHeader("Nearby") }
      if (nearby.isEmpty()) {
        item { Scanning(onScan = { scan() }, onCode = { enteringCode = true }) }
      } else {
        items(nearby, key = { it.beacon.session }) { formation ->
          NearbyCard(formation, onJoin = { join(formation) })
        }
        item {
          Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.m),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
          ) {
            Button(
              "Scan QR",
              { scan() },
              Modifier.weight(1f),
              style = ButtonStyle.Secondary,
              size = ButtonSize.Medium,
              leadingIcon = Icons.Scan,
              fillWidth = true,
            )
            Button(
              "Enter code",
              { enteringCode = true },
              Modifier.weight(1f),
              style = ButtonStyle.Secondary,
              size = ButtonSize.Medium,
              leadingIcon = Icons.Keypad,
              fillWidth = true,
            )
          }
        }
      }
      item { NavigationBarSpacer() }
    }
  }

  opened?.let { o ->
    OpportunitySheet(
      o,
      onDismiss = { opened = null },
      onStart = {
        scope.launch {
          graph
            .host(o)
            .fold(
              onSuccess = {
                opened = null
                graph.navigator.push(Screen.Session)
              },
              onFailure = {
                toaster.show(it.message ?: "Couldn't start the Formation", Tone.Negative)
              },
            )
        }
      },
    )
  }
  if (enteringCode) {
    JoinCodeSheet(
      nearby = nearby,
      onDismiss = { enteringCode = false },
      onJoin = {
        enteringCode = false
        join(it)
      },
    )
  }
}

@Composable
private fun OpportunityCard(o: Opportunity, onOpen: () -> Unit) {
  val c = Theme.colors
  val info = challengeInfo(o.challenge)
  val split = o.split()
  val now = xyz.mcxross.formation.state.now()
  Panel(
    Modifier.width(284.dp).pressable(onOpen, shape = Shapes.card, squeeze = true),
  ) {
    Column(Modifier.padding(Space.l)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        ChallengeGlyph(info, size = 36.dp)
        Spacer(Modifier.width(Space.m))
        Text(
          info?.title ?: o.challenge.value,
          style = Theme.type.headline,
          modifier = Modifier.weight(1f),
          maxLines = 2,
        )
      }
      Spacer(Modifier.height(Space.m))
      Row(verticalAlignment = Alignment.CenterVertically) {
        TierTag(o.tier)
        Spacer(Modifier.width(Space.s))
        Text(
          o.title ?: o.sponsor,
          style = Theme.type.footnote,
          color = c.contentSecondary,
          modifier = Modifier.weight(1f),
          maxLines = 1,
        )
      }
      Spacer(Modifier.height(Space.l))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Lock, "Locked", tint = c.reward, size = 22.dp)
        Spacer(Modifier.width(Space.s))
        SkrAmount(
          o.reward.format(0),
          style = Theme.type.numeral,
          color = c.content,
          coin = false,
        )
      }
      Spacer(Modifier.height(Space.l))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Slots(o.players, filled = 0)
        Spacer(Modifier.width(Space.s))
        Text("${o.players} players", style = Theme.type.subheadStrong, color = c.content)
      }
      Spacer(Modifier.height(Space.xs))
      Text(
        "${timeLeft(o.expiresAt, now)} · ${o.difficulty.label}",
        style = Theme.type.footnote,
        color = c.contentSecondary,
      )
      Spacer(Modifier.height(Space.l))
      Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
      Spacer(Modifier.height(Space.m))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Column(Modifier.weight(1f)) {
          Text("Your share", style = Theme.type.caption, color = c.contentSecondary)
          SkrAmount(
            split.owner.format(0),
            style = Theme.type.subheadStrong,
            color = c.reward,
            coin = false,
          )
        }
        Column(Modifier.weight(1f)) {
          Text("Each helper", style = Theme.type.caption, color = c.contentSecondary)
          SkrAmount(split.helper.format(0), style = Theme.type.subheadStrong, coin = false)
        }
      }
    }
  }
}

@Composable
private fun NearbyCard(formation: NearbyFormation, onJoin: () -> Unit) {
  val c = Theme.colors
  val b = formation.beacon
  val info = challengeInfo(b.challenge)
  val color = info?.let { c.light(it.light).color } ?: c.accent
  Panel(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp)) {
    Column(Modifier.padding(Space.l)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        ChallengeGlyph(info, size = 44.dp)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
          Text("${possessive(b.host)} Formation", style = Theme.type.headline, maxLines = 2)
          Text(info?.title ?: b.challenge.value, style = Theme.type.footnote, color = c.contentSecondary)
        }
        if (b.open)
          Button(
            "Join",
            onJoin,
            style = ButtonStyle.Primary,
            size = ButtonSize.Small,
            fillWidth = false,
          )
        else Tag(if (b.joined >= b.players) "Full" else "Playing")
      }
      Spacer(Modifier.height(Space.m))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Slots(b.players, b.joined, color = color)
        Spacer(Modifier.width(Space.s))
        Text(
          "${b.joined} of ${b.players} joined",
          style = Theme.type.footnote,
          color = c.contentSecondary,
        )
      }
      Spacer(Modifier.height(Space.m))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Column(Modifier.weight(1f)) {
          Text("Locked reward", style = Theme.type.caption, color = c.contentSecondary)
          SkrAmount(
            b.reward.format(0),
            style = Theme.type.subheadStrong,
            color = c.reward,
            coin = false,
          )
        }
        Column(Modifier.weight(1f)) {
          Text("Your share if unlocked", style = Theme.type.caption, color = c.contentSecondary)
          SkrAmount(b.helperShare.format(0), style = Theme.type.subheadStrong, coin = false)
        }
      }
    }
  }
}

@Composable
private fun Scanning(onScan: () -> Unit, onCode: () -> Unit) {
  val c = Theme.colors
  Column(
    Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Spinner(16.dp, color = c.contentSecondary)
      Spacer(Modifier.width(Space.s))
      Text(
        "Looking for Formations nearby",
        style = Theme.type.subheadStrong,
        modifier = Modifier.weight(1f),
      )
    }
    Spacer(Modifier.height(Space.s))
    Text(
      "Formations are started on a Seeker. When one is on your Wi-Fi, it appears here. You can also scan its code.",
      style = Theme.type.subhead,
      color = c.contentSecondary,
    )
    Spacer(Modifier.height(Space.xl))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
      Button(
        "Scan QR",
        onScan,
        Modifier.weight(1f),
        style = ButtonStyle.Secondary,
        size = ButtonSize.Medium,
        leadingIcon = Icons.Scan,
        fillWidth = true,
      )
      Button(
        "Enter code",
        onCode,
        Modifier.weight(1f),
        style = ButtonStyle.Secondary,
        size = ButtonSize.Medium,
        leadingIcon = Icons.Keypad,
        fillWidth = true,
      )
    }
  }
}

@Composable
private fun SeekerStatusRow(status: SeekerStatus, onRetry: () -> Unit) {
  val c = Theme.colors
  val modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.l)
  when (status) {
    SeekerStatus.Checking ->
      Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Spinner(16.dp, color = c.contentSecondary)
        Spacer(Modifier.width(Space.s))
        Text("Checking your Seeker…", style = Theme.type.subheadStrong, color = c.contentSecondary)
      }
    SeekerStatus.NotLinked ->
      Notice(
        "Locked SKR for this Seeker reaches it once you link. Seed Vault asks you to approve; nothing is spent.",
        modifier,
        title = "Link your Seeker",
        action = "Link",
        onAction = onRetry,
      )
    is SeekerStatus.NeedsApproval ->
      Notice(
        "Approve Formation in your Seed Vault so this Seeker can host Formations. ${status.message}",
        modifier,
        tone = Tone.Warning,
        title = "Link your Seeker",
        action = "Try again",
        onAction = onRetry,
      )
    is SeekerStatus.NoToken ->
      Notice(
        "${shortAddress(status.wallet)} doesn't hold a Seeker Genesis Token. Choose the wallet that came with this Seeker.",
        modifier,
        tone = Tone.Warning,
        title = "No Seeker Genesis Token",
        action = "Try another wallet",
        onAction = onRetry,
      )
    else -> Unit
  }
}

@Composable
private fun RewardsBanner(total: Skr, count: Int, onOpen: () -> Unit) {
  val c = Theme.colors
  Row(
    Modifier.fillMaxWidth()
      .padding(horizontal = Space.gutter, vertical = Space.l)
      .clip(Shapes.control)
      .pressable(onOpen, shape = Shapes.control)
      .background(c.surface)
      .border(1.dp, c.line, Shapes.control)
      .padding(Space.l),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    SkrCoin(24.dp)
    Spacer(Modifier.width(Space.m))
    Column(Modifier.weight(1f)) {
      SkrAmount(total.format(2), color = c.reward, coin = false)
      Text(
        "$count ${if (count == 1) "reward" else "rewards"} ready to claim",
        style = Theme.type.footnote,
        color = c.contentSecondary,
      )
    }
    Icon(Icons.ChevronRight, null, tint = c.reward)
  }
}
