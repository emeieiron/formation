package xyz.mcxross.formation.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import xyz.mcxross.formation.resources.Res
import xyz.mcxross.formation.resources.a11y_locked_reward
import xyz.mcxross.formation.resources.a11y_occupancy
import xyz.mcxross.formation.resources.action_code
import xyz.mcxross.formation.resources.action_join
import xyz.mcxross.formation.resources.action_scan
import xyz.mcxross.formation.resources.action_unlock
import xyz.mcxross.formation.resources.copy_offline_unlock
import xyz.mcxross.formation.resources.label_dev
import xyz.mcxross.formation.resources.label_nearby
import xyz.mcxross.formation.resources.label_profile
import xyz.mcxross.formation.resources.label_rewards
import xyz.mcxross.formation.resources.label_your_share
import xyz.mcxross.formation.resources.ready_reward_count
import xyz.mcxross.formation.resources.state_full
import xyz.mcxross.formation.resources.state_reward_unavailable
import xyz.mcxross.formation.resources.state_saved_game_unavailable
import xyz.mcxross.formation.resources.label_saved_result
import xyz.mcxross.formation.resources.state_playing
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.components.ButtonSize
import xyz.mcxross.formation.design.components.ButtonStyle
import xyz.mcxross.formation.design.components.motionEnabled
import xyz.mcxross.formation.design.components.LiveryRule
import xyz.mcxross.formation.design.components.LocalToaster
import xyz.mcxross.formation.design.components.NavigationBarSpacer
import xyz.mcxross.formation.design.components.Notice
import xyz.mcxross.formation.design.components.Page
import xyz.mcxross.formation.design.components.PlayerLight
import xyz.mcxross.formation.design.components.SectionHeader
import xyz.mcxross.formation.design.components.SkrAmount
import xyz.mcxross.formation.design.components.Tag
import xyz.mcxross.formation.design.components.liveryCard
import xyz.mcxross.formation.design.effects.FormationMark
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Panel
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.foundation.pressable
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Light
import xyz.mcxross.formation.design.tokens.Motion
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.design.tokens.Tone
import xyz.mcxross.formation.model.Budget
import xyz.mcxross.formation.model.Skr
import xyz.mcxross.formation.session.NearbyFormation
import xyz.mcxross.formation.state.SeekerStatus
import xyz.mcxross.formation.state.PhoneRole
import xyz.mcxross.formation.state.Links
import xyz.mcxross.formation.state.ChallengeCatalog
import xyz.mcxross.formation.ui.LocalGraph
import xyz.mcxross.formation.ui.components.Slots
import xyz.mcxross.formation.ui.components.challengeInfo
import xyz.mcxross.formation.ui.components.shortAddress
import xyz.mcxross.formation.ui.nav.Screen

@Composable
fun HomeScreen(presentation: HomePresentation) {
  val graph = LocalGraph.current
  val c = Theme.colors
  val toaster = LocalToaster.current
  val scope = rememberCoroutineScope()
  val profile by graph.identity.profile.collectAsState()
  val seeker by graph.seeker.identity.collectAsState()
  val rewards by graph.ledger.budgets.collectAsState()
  val draws by graph.ledger.draws.collectAsState()
  val rewardTime by produceState(xyz.mcxross.formation.state.now(), rewards) {
    while (true) {
      val at = xyz.mcxross.formation.state.now()
      value = at
      val expiry = rewards.filter { it.playUntil > at }.minOfOrNull { it.playUntil } ?: break
      delay((expiry - at).coerceAtLeast(1))
    }
  }
  val gameRewards = ChallengeCatalog.all.associate { game ->
    game.id to if (seeker != null) ChallengeCatalog.rewardsFor(game.id, rewards, rewardTime) else emptyList()
  }
  val tickets by graph.ledger.tickets.collectAsState()
  val problem by graph.ledger.problem.collectAsState()
  // Discovery starts automatically with Home; only results and failures have UI.
  var scanAttempt by remember { mutableStateOf(0) }
  val discovery by graph.nearby.status.collectAsState(xyz.mcxross.formation.link.DiscoveryStatus.Searching)
  val nearby by
    produceState(emptyList<NearbyFormation>(), scanAttempt) { graph.nearby.scan().collect { value = it } }
  var opened by remember { mutableStateOf<Pair<xyz.mcxross.formation.challenge.Challenge<*, *>, Budget>?>(null) }
  var gameActions by remember { mutableStateOf<xyz.mcxross.formation.challenge.Challenge<*, *>?>(null) }
  val rewardUnavailable = stringResource(Res.string.state_reward_unavailable)
  var enteringCode by remember { mutableStateOf(false) }
  val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
  val moves = motionEnabled()
  val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED) && graph.navigator.current.screen == Screen.Home &&
    opened == null && gameActions == null && !enteringCode
  val listState = rememberLazyListState()
  val visibleKeys by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.map { it.key }.toSet() } }
  val coverTop = with(LocalDensity.current) { (Space.l + 24.dp).roundToPx() }
  val coverBottom = with(LocalDensity.current) { (Space.l + 24.dp + 150.dp).roundToPx() }
  val coverVisible by remember(listState, coverTop, coverBottom) { derivedStateOf {
    val layout = listState.layoutInfo
    layout.visibleItemsInfo.firstOrNull { it.key == "games-catalog" }?.let {
      it.offset + coverBottom > layout.viewportStartOffset && it.offset + coverTop < layout.viewportEndOffset
    } ?: false
  } }
  val entrance = remember { Animatable(if (!presentation.entered && moves && active) 0f else 1f) }
  LaunchedEffect(active, moves) {
    if (!presentation.entered && active) {
      presentation.entered = true
      if (moves) entrance.animateTo(1f, Motion.emphasized(520)) else entrance.snapTo(1f)
    } else entrance.snapTo(1f)
  }
  val status by graph.seeker.status.collectAsState()
  val role by graph.role.collectAsState()
  val developer = graph.platform.config.developer
  val pendingWins by graph.pending.pending.collectAsState()
  val completed by graph.completed.entries.collectAsState()

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
      FormationMark(Modifier.size(width = 40.dp, height = 24.dp), progress = { entrance.value })
      Spacer(Modifier.width(Space.s))
      Text("Formation", style = Theme.type.title3)
      if (developer) {
        Spacer(Modifier.width(Space.s))
        Tag(stringResource(Res.string.label_dev), tone = Tone.Warning)
      }
      Spacer(Modifier.weight(1f))
      profile?.let { p ->
        Box(
          Modifier.pressable(
              { graph.navigator.push(Screen.Settings) },
              shape = Shapes.control,
              onClickLabel = stringResource(Res.string.label_profile),
            )
            .padding(6.dp)
        ) {
          PlayerLight(p.name, c.light(p.light), size = 36.dp, seeker = seeker != null)
        }
      }
    }

    Spacer(Modifier.height(Space.l))
    LiveryRule(Modifier.padding(horizontal = Space.gutter).drawWithContent {
      clipRect(right = size.width * entrance.value) { this@drawWithContent.drawContent() }
    })
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = Space.x4l)) {
      item {
        Column(Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.l)) {
          PlayerName(profile?.name ?: "Formation")
        }
      }

      val unclaimed = tickets.filter { !it.claimed && it.unlocked && !it.lapsed }
      if (unclaimed.isNotEmpty()) {
        item(key = "ready-rewards") {
          RewardsBanner(
            Skr(unclaimed.sumOf { it.amount.units }),
            unclaimed.size,
            keys = unclaimed.map { "${it.opportunity.value}:${it.index}" }.toSet(),
            presentation = presentation,
            active = active && "ready-rewards" in visibleKeys,
            onOpen = { graph.navigator.push(Screen.Rewards) },
          )
        }
      }

      val unfinished = completed.filter { record ->
        val won = record.snapshot.stage as? xyz.mcxross.formation.session.Stage.Won
        won != null && !won.seal.complete
      }
      items(unfinished, key = { "completion-" + it.snapshot.formation.session }) { record ->
        val resumable = ChallengeCatalog.supports(record.snapshot.formation.opportunity)
        Notice(if (resumable) "Reconnect the group to finish saving this win." else stringResource(Res.string.state_saved_game_unavailable),
          Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
          title = if (resumable) "Finish saving your win" else stringResource(Res.string.label_saved_result),
          action = if (resumable) "Resume" else null, onAction = { scope.launch { graph.resumeCompletion(record).onFailure {
            toaster.show(it.message ?: "Couldn't resume", Tone.Warning)
          } } })
      }
      val me = seeker
      if (me != null && pendingWins.isNotEmpty()) {
        items(pendingWins, key = { "pending-" + it.opportunity.id.value }) { win ->
          Notice(
            if (win.unlocked) "Some wallet payments are still pending. Retry once online." else stringResource(Res.string.copy_offline_unlock),
            Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
            tone = Tone.Warning,
            title = "${win.opportunity.reward.format(0)} SKR",
            action = if (win.unlocked) "Retry payments" else stringResource(Res.string.action_unlock),
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
      // A phone that isn't a Seeker plays by joining one, so joining comes first. A Seeker hosts, so its
      // setup and games come first.
      fun LazyListScope.nearbySection() {
        item { SectionHeader(stringResource(Res.string.label_nearby)) }
        (discovery as? xyz.mcxross.formation.link.DiscoveryStatus.Failed)?.let { failure ->
          item { Notice(failure.reason.message, Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
            title = "Nearby unavailable", tone = Tone.Warning, action = "Retry", onAction = { scanAttempt++ }) }
        }
        if (role == PhoneRole.PLAYER && nearby.isEmpty()) item(key = "join-intro") {
          JoinIntro(
            searching = discovery !is xyz.mcxross.formation.link.DiscoveryStatus.Failed,
            onLink = { scope.launch { graph.seeker.link() } },
            onPretend = if (developer) {{ graph.seeker.pretend(true, graph.identity.claimAddress) }} else null,
            modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
          )
        }
        if (role == PhoneRole.PLAYER) linkProblem(status)?.let { (title, text) ->
          item { Notice(text, Modifier.padding(horizontal = Space.gutter, vertical = Space.s), title = title, tone = Tone.Warning) }
        }
        items(nearby, key = { it.beacon.session }) { formation ->
          NearbyCard(formation, onJoin = { join(formation) }, presentation = presentation,
            active = active && formation.beacon.session in visibleKeys)
        }
        item {
          JoinControls(
            onScan = { scan() },
            onCode = { enteringCode = true },
            modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.m),
          )
        }
      }
      val hostable = gameRewards.values.sumOf { it.size }
      fun LazyListScope.games() = gameCatalog(ChallengeCatalog.all, canHost = me != null,
        seekerPhone = role != PhoneRole.PLAYER,
        playable = gameRewards.filterValues { it.isNotEmpty() }.keys, testHost = role == PhoneRole.TEST_HOST,
        motionActive = active && coverVisible,
        entrance = { entrance.value },
        onOpen = { gameActions = it })

      if (role == PhoneRole.PLAYER) {
        nearbySection()
        games()
      } else {
        if (role != PhoneRole.TEST_HOST && (me == null || hostable == 0)) item(key = "seeker-setup") {
          SeekerSetup(status, me?.wallet, hostable,
            onLink = { scope.launch { graph.seeker.link() } },
            modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s))
        }
        games()
        if (me != null) items(draws, key = { "draw-" + it.contest }) { draw ->
          OpenDrawRow(draw, Modifier.padding(horizontal = Space.gutter, vertical = Space.s)) {
            scope.launch {
              graph.ledger.enter(me, draw).fold(
                onSuccess = { toaster.show("Entered the draw", Tone.Positive) },
                onFailure = { toaster.show(it.message ?: "Couldn't enter the draw", Tone.Negative) },
              )
            }
          }
        }
        if (me != null) problem?.let {
          item {
            Notice(it, Modifier.padding(horizontal = Space.gutter, vertical = Space.s), tone = Tone.Warning)
          }
        }
        nearbySection()
      }
      item {
        Spacer(Modifier.height(Space.xl))
        Row(
          Modifier.fillMaxWidth().padding(horizontal = Space.gutter)
            .pressable({ graph.navigator.push(Screen.Rewards) }, shape = Shapes.control)
            .padding(vertical = Space.l),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(stringResource(Res.string.label_rewards), Modifier.weight(1f), style = Theme.type.bodyStrong)
          Icon(Icons.ArrowUpRight, null)
        }
      }
      item { NavigationBarSpacer() }
    }
  }

  gameActions?.let { game ->
    GameActionSheet(game,
      rewards = gameRewards[game.id].orEmpty(),
      onDismiss = { gameActions = null },
      onReward = { reward ->
        val current = ChallengeCatalog.rewardsFor(game.id, graph.ledger.budgets.value, xyz.mcxross.formation.state.now())
          .firstOrNull { it.id == reward.id }
        if (current != null && graph.seeker.identity.value != null) {
          gameActions = null
          opened = game to current
        } else toaster.show(rewardUnavailable, Tone.Warning)
      },
    )
  }
  opened?.let { (game, budget) ->
    OpportunitySheet(
      game,
      budget,
      onDismiss = { opened = null },
      onStart = { o ->
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
private fun NearbyCard(formation: NearbyFormation, onJoin: () -> Unit, presentation: HomePresentation, active: Boolean) {
  val c = Theme.colors
  val b = formation.beacon
  val info = challengeInfo(b.challenge)
  val color = c.accent
  val moves = motionEnabled()
  val arrival = remember(b.session) { Animatable(1f) }
  LaunchedEffect(b.session, active, moves) {
    if (active && presentation.revealedSessions.add(b.session) && moves) {
      arrival.snapTo(0f)
      arrival.animateTo(1f, Motion.emphasized(420))
    } else arrival.snapTo(1f)
  }
  Panel(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp).graphicsLayer {
    translationY = (1 - arrival.value) * 16.dp.toPx()
    alpha = .4f + .6f * arrival.value
  }.liveryCard()) {
    Column(Modifier.padding(Space.l)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        PlayerLight(b.host, Light("Host", c.content, c.onInverse), size = 44.dp)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
          Text(b.host, style = Theme.type.title2, maxLines = 2)
          Text(info?.title ?: b.challenge.value, style = Theme.type.footnote, color = c.contentSecondary)
        }
        if (b.protocol != xyz.mcxross.formation.session.PROTOCOL_VERSION)
          Tag("Update needed", tone = Tone.Warning)
        else if (b.open)
          Button(
            stringResource(Res.string.action_join),
            onJoin,
            style = ButtonStyle.Primary,
            size = ButtonSize.Small,
            fillWidth = false,
          )
        else Tag(stringResource(if (b.joined >= b.players) Res.string.state_full else Res.string.state_playing))
      }
      Spacer(Modifier.height(Space.m))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Slots(b.players, b.joined, color = color, animate = active && moves)
        Spacer(Modifier.width(Space.s))
        val occupancy = stringResource(Res.string.a11y_occupancy, b.joined, b.players)
        Text(
          "${b.joined}/${b.players}",
          Modifier.clearAndSetSemantics { contentDescription = occupancy },
          style = Theme.type.footnote,
          color = c.contentSecondary,
        )
      }
      Spacer(Modifier.height(Space.m))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Column(Modifier.weight(1f)) {
          Icon(Icons.Lock, stringResource(Res.string.a11y_locked_reward), tint = c.contentSecondary, size = 16.dp)
          SkrAmount(
            b.reward.format(0),
            style = Theme.type.numeral,
            color = c.reward,
            coin = false,
          )
        }
        Column(Modifier.weight(1f)) {
          Text(stringResource(Res.string.label_your_share), style = Theme.type.caption, color = c.contentSecondary)
          SkrAmount(b.helperShare.format(0), style = Theme.type.subheadStrong, coin = false)
        }
      }
    }
  }
}

// A failed link from the join panel, as a title and a message.
private fun linkProblem(status: SeekerStatus): Pair<String, String>? = when (status) {
  is SeekerStatus.NeedsApproval -> "Not linked yet" to status.message
  is SeekerStatus.NoToken -> "No Seeker Genesis Token" to "${shortAddress(status.wallet)} doesn't hold a Seeker Genesis Token."
  else -> null
}

@Composable
private fun RewardsBanner(total: Skr, count: Int, keys: Set<String>, presentation: HomePresentation, active: Boolean, onOpen: () -> Unit) {
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
    ReadyRewardPass(keys, presentation, active)
    Spacer(Modifier.width(Space.m))
    Column(Modifier.weight(1f)) {
      SkrAmount(total.format(2), color = c.reward, coin = false)
      Text(pluralStringResource(Res.plurals.ready_reward_count, count, count),
        style = Theme.type.footnote, color = c.contentSecondary)
    }
    Icon(Icons.ChevronRight, null, tint = c.reward)
  }
}

@Composable
private fun JoinControls(onScan: () -> Unit, onCode: () -> Unit, modifier: Modifier = Modifier) {
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val stacked = maxWidth < 300.dp && androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f
    if (stacked) Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
      Button(stringResource(Res.string.action_scan), onScan, style = ButtonStyle.Secondary, leadingIcon = Icons.Scan)
      Button(stringResource(Res.string.action_code), onCode, style = ButtonStyle.Secondary, leadingIcon = Icons.Keypad)
    } else Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
      Button(stringResource(Res.string.action_scan), onScan, Modifier.weight(1f), style = ButtonStyle.Secondary,
        size = ButtonSize.Medium, leadingIcon = Icons.Scan, fillWidth = true)
      Button(stringResource(Res.string.action_code), onCode, Modifier.weight(1f), style = ButtonStyle.Secondary,
        size = ButtonSize.Medium, leadingIcon = Icons.Keypad, fillWidth = true)
    }
  }
}

@Composable
private fun PlayerName(name: String) {
  val hero = Theme.type.hero.copy(fontStyle = FontStyle.Italic)
  val measurer = rememberTextMeasurer()
  val edgeAllowance = with(LocalDensity.current) { 8.dp.roundToPx() }
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val nameWidth = measurer.measure(name, style = hero, softWrap = false).size.width
    val fit = ((constraints.maxWidth - edgeAllowance).toFloat() / nameWidth.coerceAtLeast(1))
      .coerceIn(0.1f, 1f)
    Text(
      name,
      style = hero.copy(fontSize = hero.fontSize * fit, lineHeight = hero.lineHeight * fit),
      maxLines = 2,
    )
  }
}
