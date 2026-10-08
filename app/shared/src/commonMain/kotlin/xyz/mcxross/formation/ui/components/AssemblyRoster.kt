package xyz.mcxross.formation.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.PlayerLight
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.tokens.Light
import xyz.mcxross.formation.design.tokens.Motion
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.resources.Res
import xyz.mcxross.formation.resources.label_you
import xyz.mcxross.formation.resources.state_disconnected
import xyz.mcxross.formation.resources.state_open_place
import xyz.mcxross.formation.session.Player

/** Stable player keys make arrivals animate once, rather than on every snapshot update. */
@Composable
internal fun AssemblyRoster(
  players: List<Player>,
  slots: Int,
  me: PlayerId,
  modifier: Modifier = Modifier,
  compact: Boolean = false,
) {
  val c = Theme.colors
  val you = stringResource(Res.string.label_you)
  val disconnected = stringResource(Res.string.state_disconnected)
  val openPlace = stringResource(Res.string.state_open_place)
  val plateSize = if (slots <= 2 && !compact) 100.dp else 68.dp
  val stagger = if (slots <= 2 && !compact) 48.dp else 20.dp
  val roster = players + List((slots - players.size).coerceAtLeast(0)) { null }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xl)) {
    roster.chunked(2).forEach { pair ->
      val linked = pair.size == 2 && pair.all { it?.connected == true }
      val connection = remember { Animatable(0f) }
      LaunchedEffect(linked) {
        connection.animateTo(if (linked) 1f else 0f, Motion.emphasized(400, delay = 150))
      }
      Box(Modifier.fillMaxWidth()) {
        if (pair.size == 2) {
          Canvas(Modifier.fillMaxWidth().height(plateSize + stagger)) {
            val columnWidth = (size.width - Space.m.toPx()) / 2
            val leftEdge = if (pair[0] == null) 0.5f else 0.42f
            val rightEdge = if (pair[1] == null) 0.5f else 0.42f
            val from =
              Offset(
                columnWidth / 2 + plateSize.toPx() * leftEdge,
                plateSize.toPx() * 0.5f,
              )
            val to =
              Offset(
                size.width - columnWidth / 2 - plateSize.toPx() * rightEdge,
                plateSize.toPx() * 0.5f + stagger.toPx(),
              )
            val path =
              Path().apply {
                moveTo(from.x, from.y)
                cubicTo(from.x + 24.dp.toPx(), from.y, to.x - 24.dp.toPx(), to.y, to.x, to.y)
              }
            drawPath(path, c.lineStrong, style = Stroke(1.dp.toPx()))
            val measure = PathMeasure().apply { setPath(path, false) }
            val reveal = Path()
            measure.getSegment(0f, measure.length * connection.value, reveal, true)
            drawPath(reveal, c.accent, style = Stroke(3.dp.toPx()))
          }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
          pair.forEachIndexed { index, player ->
            key(player?.id ?: "open-$index") {
              val arrival = remember { Animatable(if (player == null) 1f else 0f) }
              LaunchedEffect(player?.id) { arrival.animateTo(1f, Motion.emphasized()) }
              val opacity by
                animateFloatAsState(
                  if (player?.connected == false) 0.45f else 1f,
                  Motion.standard(),
                  label = "connection-state",
                )
              Column(
                Modifier.weight(1f)
                  .offset(y = if (index == 1) stagger else 0.dp)
                  .graphicsLayer {
                    translationX = (1 - arrival.value) * 28.dp.toPx()
                    alpha = arrival.value * opacity
                  }
                  .clearAndSetSemantics {
                    contentDescription =
                      player?.let {
                        listOfNotNull(
                            it.name,
                            if (it.id == me) you else null,
                            if (it.seeker) "Seeker" else null,
                            if (!it.connected) disconnected else null,
                          )
                          .joinToString(", ")
                      } ?: openPlace
                  },
                horizontalAlignment = Alignment.CenterHorizontally,
              ) {
                if (player != null) {
                  PlayerLight(
                    player.name,
                    if (player.seeker) Light("Seeker", c.content, c.onInverse)
                    else c.light(player.light),
                    size = plateSize,
                  )
                } else {
                  Canvas(Modifier.size(plateSize)) {
                    val stroke = 1.dp.toPx()
                    drawRoundRect(
                      c.lineStrong,
                      topLeft = Offset(stroke / 2, stroke / 2),
                      size = Size(size.width - stroke, size.height - stroke),
                      cornerRadius = CornerRadius(6.dp.toPx()),
                      style =
                        Stroke(
                          stroke,
                          pathEffect =
                            PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                        ),
                    )
                  }
                }
                Spacer(Modifier.height(Space.s))
                Text(
                  player?.name ?: "—",
                  Modifier.fillMaxWidth(),
                  style = Theme.type.subheadStrong,
                  maxLines = 1,
                  textAlign = TextAlign.Center,
                )
                val role =
                  when {
                    player == null -> null
                    !player.connected -> disconnected
                    else ->
                      listOfNotNull(
                          "Seeker".takeIf { player.seeker },
                          you.takeIf { player.id == me },
                        )
                        .joinToString(" · ")
                        .takeIf { it.isNotEmpty() }
                  }
                if (role != null)
                  Text(
                    role,
                    style = Theme.type.caption,
                    color = c.contentSecondary,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                  )
              }
            }
          }
        }
        // offset() intentionally moves drawing only; reserve the same amount below the row.
      }
      Spacer(Modifier.height(stagger))
    }
  }
}
