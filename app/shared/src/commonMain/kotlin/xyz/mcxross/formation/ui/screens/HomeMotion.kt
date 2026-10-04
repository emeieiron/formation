package xyz.mcxross.formation.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.RewardPass
import xyz.mcxross.formation.design.components.motionEnabled
import xyz.mcxross.formation.design.tokens.Motion
import kotlin.math.PI
import kotlin.math.sin

/** Presentation history belongs to this app UI session, not persisted game state. */
class HomePresentation {
  var entered = false
  val revealedRewards = mutableSetOf<String>()
  val revealedSessions = mutableSetOf<String>()
}

@Composable
internal fun ReadyRewardPass(keys: Set<String>, presentation: HomePresentation, active: Boolean) {
  val reveal = remember { Animatable(1f) }
  val moves = motionEnabled()
  val highlight = Theme.colors.rewardHighlight
  LaunchedEffect(keys, active, moves) {
    if (!active || !moves) { reveal.snapTo(1f); return@LaunchedEffect }
    val new = keys.any { it !in presentation.revealedRewards }
    presentation.revealedRewards.addAll(keys)
    if (new) {
      reveal.snapTo(0f)
      reveal.animateTo(1f, Motion.emphasized(900))
    }
  }
  RewardPass(Modifier.size(64.dp, 40.dp).clearAndSetSemantics {}.graphicsLayer {
    rotationY = sin(reveal.value * PI).toFloat() * -18f
    rotationZ = (1 - reveal.value) * -5f
    cameraDistance = 14f * density
  }.drawWithContent {
    drawContent()
    val p = reveal.value
    if (p > 0f && p < 1f) {
      val x = (-.3f + 1.6f * p) * size.width
      drawRect(Brush.linearGradient(listOf(Color.Transparent, highlight.copy(alpha = .18f), Color.Transparent),
        Offset(x - size.width * .2f, 0f), Offset(x + size.width * .2f, size.height)))
    }
  })
}
