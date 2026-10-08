package xyz.mcxross.formation.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.mcxross.formation.challenge.rememberHostNow
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.BottomActions
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.components.ButtonStyle
import xyz.mcxross.formation.design.components.LiveryRule
import xyz.mcxross.formation.design.components.Overline
import xyz.mcxross.formation.design.components.ReadyDots
import xyz.mcxross.formation.design.components.TextButton
import xyz.mcxross.formation.design.components.TopBar
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.resources.Res
import xyz.mcxross.formation.resources.sensor_waiting
import xyz.mcxross.formation.session.SessionSnapshot
import xyz.mcxross.formation.session.Stage
import xyz.mcxross.formation.state.ActiveSession
import xyz.mcxross.formation.state.ChallengeCatalog
import xyz.mcxross.formation.ui.components.ChallengeGlyph
import xyz.mcxross.formation.ui.screens.HowToPlay

@Composable
internal fun Briefing(
  session: ActiveSession,
  snapshot: SessionSnapshot,
  stage: Stage.Briefing,
  me: PlayerId,
  onLeave: () -> Unit,
) {
  val c = Theme.colors
  val o = snapshot.formation.opportunity
  val challenge = ChallengeCatalog[o.challenge]
  val info = challenge?.info
  val now by rememberHostNow(session.client.sync)
  val ready = snapshot.player(me)?.ready == true
  val readyCount = snapshot.players.count { it.ready }
  val seeker = snapshot.seeker?.id
  val role =
    if (challenge != null && seeker != null)
      challenge.role(snapshot.players.map { it.id }, seeker, me)
    else null
  Column(Modifier.fillMaxSize()) {
    TopBar(onBack = onLeave, backIcon = Icons.Close)
    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter)
    ) {
      LiveryRule()
      Spacer(Modifier.height(Space.xl))
      ChallengeGlyph(info, size = 48.dp)
      Spacer(Modifier.height(Space.l))
      Text((info?.title ?: o.challenge.value).uppercase(), style = Theme.type.title1)
      Text(info?.tagline ?: "", style = Theme.type.body, color = c.contentSecondary)
      Spacer(Modifier.height(Space.xl))
      Overline("The goal")
      Spacer(Modifier.height(Space.s))
      Text(challenge?.goal(o.players, o.difficulty) ?: "", style = Theme.type.bodyStrong)
      role?.let {
        Spacer(Modifier.height(Space.xl))
        Row(verticalAlignment = Alignment.Top) {
          Icon(it.icon, null, tint = c.contentSecondary, size = 24.dp)
          Spacer(Modifier.width(Space.m))
          Column(Modifier.weight(1f)) {
            Overline("Your role")
            Spacer(Modifier.height(Space.xs))
            Text(it.title, style = Theme.type.subheadStrong)
            Text(it.text, style = Theme.type.subhead, color = c.contentSecondary)
          }
        }
      }
      Spacer(Modifier.height(Space.xl))
      val introduction = challenge?.introduction
      if (introduction != null) introduction() else info?.let { HowToPlay(it) }
      Spacer(Modifier.height(Space.xl))
    }
    BottomActions {
      SensorStatus(session, Modifier.fillMaxWidth())
      Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.s),
      ) {
        val left = ((stage.until - now) / 1000).coerceAtLeast(0)
        Text(
          when {
            snapshot.players.any { !it.connected } -> "Waiting for the group to reconnect"
            snapshot.players.any { !it.sensorReady } -> stringResource(Res.string.sensor_waiting)
            snapshot.players.any { !it.clockReady } -> "Synchronizing phones…"
            else -> "$readyCount of ${snapshot.players.size} ready · starts in ${left}s"
          },
          style = Theme.type.footnote,
          color = c.contentSecondary,
        )
        ReadyDots(snapshot.players.map { p -> if (p.ready) c.light(p.light).color else null })
      }
      Button(
        if (ready) "Ready" else "I'm ready",
        { session.ready(!ready) },
        style = if (ready) ButtonStyle.Secondary else ButtonStyle.Primary,
        leadingIcon = if (ready) Icons.Check else null,
        enabled = snapshot.player(me)?.sensorReady == true,
      )
      if (session.isHost)
        TextButton(
          "Start now",
          { session.startNow() },
          Modifier.align(Alignment.CenterHorizontally),
          enabled = snapshot.players.all { it.sensorReady },
        )
    }
  }
}
