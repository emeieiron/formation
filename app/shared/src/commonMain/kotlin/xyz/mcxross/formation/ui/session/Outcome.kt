package xyz.mcxross.formation.ui.session

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.BottomActions
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.components.ButtonStyle
import xyz.mcxross.formation.design.components.Notice
import xyz.mcxross.formation.design.components.ReadyDots
import xyz.mcxross.formation.design.components.RollingNumber
import xyz.mcxross.formation.design.components.SkrCoin
import xyz.mcxross.formation.design.components.Spinner
import xyz.mcxross.formation.design.components.Stat
import xyz.mcxross.formation.design.effects.FormationRing
import xyz.mcxross.formation.design.effects.ParticleLayer
import xyz.mcxross.formation.design.effects.rememberParticles
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.design.tokens.Tone
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.model.RewardSplit
import xyz.mcxross.formation.session.RoundResult
import xyz.mcxross.formation.session.SessionSnapshot
import xyz.mcxross.formation.session.Stage
import xyz.mcxross.formation.session.Unlock
import xyz.mcxross.formation.state.ActiveSession
import xyz.mcxross.formation.ui.LocalGraph
import xyz.mcxross.formation.ui.components.MwaWallets
import xyz.mcxross.formation.ui.components.RewardSplitView
import xyz.mcxross.formation.ui.components.rememberWalletInstalled
import xyz.mcxross.formation.ui.components.shortAddress
import xyz.mcxross.formation.ui.nav.Screen

@Composable
internal fun Won(
  session: ActiveSession,
  snapshot: SessionSnapshot,
  stage: Stage.Won,
  me: PlayerId,
  onDone: () -> Unit,
) {
  val c = Theme.colors
  val graph = LocalGraph.current
  val scope = rememberCoroutineScope()
  val particles = rememberParticles()
  val seal = stage.seal
  val share = seal.roster.firstOrNull { it.player == me }
  val mine = if (share != null) share.amount else seal.ownerAmount
  val walletReady = rememberWalletInstalled(graph.platform)
  val suggested = MwaWallets.first()
  var unlocking by remember { mutableStateOf(false) }
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val density = LocalDensity.current
    val width = with(density) { maxWidth.toPx() }
    val height = with(density) { maxHeight.toPx() }
    LaunchedEffect(Unit) {
      graph.platform.haptics.heavy()
      particles.confetti(width, c.lights.map { it.color } + c.reward)
    }
    LaunchedEffect(stage.unlock is Unlock.Unlocked) {
      if (stage.unlock is Unlock.Unlocked) {
        graph.platform.haptics.heavy()
        particles.burst(
          Offset(width / 2, height * 0.3f),
          listOf(c.reward, c.rewardDeep, androidx.compose.ui.graphics.Color.White),
          count = 60,
          speed = 1_300f,
        )
      }
    }
    Column(Modifier.fillMaxSize()) {
      Column(
        Modifier.weight(1f)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = Space.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Spacer(Modifier.height(Space.xl))
        FormationRing(
          members = snapshot.players.map { it.toRing(me, c) },
          modifier = Modifier.size(250.dp),
          complete = true,
          lightSize = 40.dp,
          showNames = false,
        ) {
          SkrCoin(64.dp, spin = true)
        }
        Spacer(Modifier.height(Space.l))
        Text("Formation complete", style = Theme.type.hero, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.xs))
        Text(
          stage.result.headline,
          style = Theme.type.body,
          color = c.contentSecondary,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Space.xl))
        Stats(stage.result)
        Spacer(Modifier.height(Space.xl))
        AnimatedContent(
          targetState =
            when {
              !seal.complete -> "sealing"
              else -> stage.unlock::class.simpleName ?: ""
            },
          transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith fadeOut() },
          label = "unlock",
        ) { key ->
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
              key == "sealing" -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Spinner(16.dp, color = c.contentSecondary)
                  Spacer(Modifier.width(Space.s))
                  Text(
                    "Sealing · ${seal.signed.size} of ${seal.required.size} phones",
                    style = Theme.type.subheadStrong,
                    color = c.contentSecondary,
                  )
                }
                Spacer(Modifier.height(Space.m))
                ReadyDots(
                  seal.required.map { id ->
                    if (id in seal.signed) snapshot.player(id)?.let { c.light(it.light).color }
                    else null
                  }
                )
              }
              stage.unlock is Unlock.Unlocked -> {
                Text("YOU EARNED", style = Theme.type.overline, color = c.reward)
                Spacer(Modifier.height(Space.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                  SkrCoin(40.dp)
                  Spacer(Modifier.width(Space.s))
                  RollingNumber(mine.format(2), style = Theme.type.numeralLarge, color = c.content)
                  Text(" SKR", style = Theme.type.title3, color = c.contentSecondary)
                }
                Spacer(Modifier.height(Space.s))
                Text(
                  when {
                    share == null -> "Your share went straight to your Seeker's wallet."
                    share.wallet != null && me in (stage.unlock as Unlock.Unlocked).paid ->
                      "It's in your wallet, ${shortAddress(share.wallet.orEmpty())}."
                    walletReady -> "It's yours to claim, now or whenever you're ready."
                    else ->
                      "You need a Solana wallet to claim your share. Get ${suggested.name} now, or claim it later."
                  },
                  style = Theme.type.subhead,
                  color = c.contentSecondary,
                  textAlign = TextAlign.Center,
                )
              }
              stage.unlock is Unlock.Unlocking -> {
                Spinner(28.dp, color = c.reward)
                Spacer(Modifier.height(Space.m))
                Text(
                  "Unlocking the reward…",
                  style = Theme.type.subheadStrong,
                  color = c.contentSecondary,
                )
              }
              stage.unlock is Unlock.Failed ->
                Notice(
                  if (session.isHost)
                    "The win is saved on this Seeker. It unlocks from Home once you're online. ${(stage.unlock as Unlock.Failed).message}"
                  else
                    "${snapshot.formation.host}'s Seeker couldn't unlock yet. Your share is saved in Rewards and becomes claimable once it does.",
                  tone = Tone.Warning,
                  title = "Couldn't unlock yet",
                )
              else -> {
                Text(
                  "SEALED BY ALL ${seal.required.size}",
                  style = Theme.type.overline,
                  color = c.positive,
                )
                Spacer(Modifier.height(Space.s))
                Text(
                  if (session.isHost) "Unlock it and everyone gets their share."
                  else "Waiting for ${snapshot.formation.host} to unlock the reward…",
                  style = Theme.type.body,
                  color = c.contentSecondary,
                  textAlign = TextAlign.Center,
                )
              }
            }
          }
        }
        Spacer(Modifier.height(Space.xl))
        RewardSplitView(
          RewardSplit(
            snapshot.formation.opportunity.reward,
            seal.ownerAmount,
            seal.roster.firstOrNull()?.amount ?: mine,
            seal.roster.size,
          ),
          ownerLabel = "${snapshot.formation.host}, the Seeker",
        )
        Spacer(Modifier.height(Space.xl))
      }
      BottomActions {
        when {
          stage.unlock is Unlock.Unlocked &&
            share != null &&
            me in (stage.unlock as Unlock.Unlocked).paid -> Button("Done", onDone)
          stage.unlock is Unlock.Unlocked && share != null && !walletReady -> {
            Button(
              "Get ${suggested.name}",
              { graph.platform.external.openUrl(suggested.storeUrl) },
              style = ButtonStyle.Reward,
              leadingIcon = Icons.Wallet,
            )
            Button(
              "Claim later",
              {
                onDone()
                graph.navigator.push(Screen.Rewards)
              },
              style = ButtonStyle.Ghost,
            )
          }
          stage.unlock is Unlock.Unlocked && share != null -> {
            Button(
              "Claim ${mine.format(2)} SKR",
              {
                onDone()
                graph.navigator.push(Screen.Rewards)
              },
              style = ButtonStyle.Reward,
              leadingIcon = Icons.Wallet,
            )
            Button("Later", onDone, style = ButtonStyle.Ghost)
          }
          stage.unlock is Unlock.Unlocked -> Button("Done", onDone)
          session.isHost &&
            seal.complete &&
            (stage.unlock is Unlock.Waiting || stage.unlock is Unlock.Failed) ->
            Button(
              "Unlock ${snapshot.formation.opportunity.reward.format(0)} SKR",
              {
                unlocking = true
                scope.launch {
                  session.unlock()
                  unlocking = false
                }
              },
              style = ButtonStyle.Primary,
              loading = unlocking,
              leadingIcon = Icons.Unlock,
            )
          else -> Spacer(Modifier.height(1.dp))
        }
      }
    }
    ParticleLayer(particles, Modifier.fillMaxSize())
  }
}

@Composable
private fun Stats(result: RoundResult) {
  if (result.stats.isEmpty()) return
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
    result.stats.forEach {
      Stat(
        it.value,
        it.label,
        horizontalAlignment = Alignment.CenterHorizontally,
        valueStyle = Theme.type.numeralSmall,
      )
    }
  }
}

@Composable
internal fun Lost(
  session: ActiveSession,
  snapshot: SessionSnapshot,
  stage: Stage.Lost,
  me: PlayerId,
  onLeave: () -> Unit,
) {
  val c = Theme.colors
  val culprit = stage.result.culprit?.let { snapshot.player(it) }
  Column(Modifier.fillMaxSize()) {
    Column(
      Modifier.weight(1f).padding(horizontal = Space.gutter),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Spacer(Modifier.weight(1f))
      FormationRing(
        members = snapshot.players.map { it.toRing(me, c) },
        modifier = Modifier.size(220.dp),
        lightSize = 36.dp,
        showNames = false,
      ) {
        Icon(Icons.Alert, null, tint = c.negative, size = 40.dp)
      }
      Spacer(Modifier.height(Space.xl))
      Text(
        "The Formation broke",
        style = Theme.type.display,
        color = c.content,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(Space.s))
      Text(
        culprit?.let { "${if (it.id == me) "You" else it.name} ${stage.result.headline}" }
          ?: stage.result.headline.replaceFirstChar { it.uppercase() },
        style = Theme.type.body,
        color = c.contentSecondary,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(Space.xl))
      Stats(stage.result)
      Spacer(Modifier.height(Space.l))
      Text(
        "The reward is still locked. Try again together.",
        style = Theme.type.footnote,
        color = c.contentTertiary,
      )
      Spacer(Modifier.weight(1.2f))
    }
    BottomActions {
      if (session.isHost) {
        Button("Run it back", { session.runItBack() }, leadingIcon = Icons.Refresh)
        Button("Back to the lobby", { session.backToLobby() }, style = ButtonStyle.Ghost)
      } else {
        Row(
          Modifier.fillMaxWidth().height(56.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center,
        ) {
          Spinner(16.dp, color = c.contentSecondary)
          Spacer(Modifier.width(Space.s))
          Text(
            "Waiting for ${snapshot.formation.host}…",
            style = Theme.type.subheadStrong,
            color = c.contentSecondary,
          )
        }
        Button("Leave", onLeave, style = ButtonStyle.Ghost)
      }
    }
  }
}
