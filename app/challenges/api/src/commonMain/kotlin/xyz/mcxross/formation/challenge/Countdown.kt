package xyz.mcxross.formation.challenge

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.tokens.Motion
import xyz.mcxross.formation.session.ClockSync

@Composable
fun Countdown(
  goAt: Long,
  clock: ClockSync,
  modifier: Modifier = Modifier,
  // Null skips the word: the go beat still fires, but nothing is drawn over the stage.
  go: String? = "Go",
  onBeat: (Int) -> Unit = {},
) {
  val now by rememberHostNow(clock)
  val left = goAt - now
  val beat =
    when {
      left > 3_000 -> 4
      left > 0 -> ((left + 999) / 1_000).toInt()
      left > -700 -> 0
      else -> -1
    }
  val onBeatNow by rememberUpdatedState(onBeat)
  LaunchedEffect(beat) { if (beat in 0..3) onBeatNow(beat) }
  Box(modifier, contentAlignment = Alignment.Center) {
    AnimatedContent(
      beat,
      transitionSpec = {
        (scaleIn(Motion.bouncy(), initialScale = 1.6f) + fadeIn(tween(120))) togetherWith
          (scaleOut(tween(200), targetScale = 0.6f) + fadeOut(tween(200)))
      },
      label = "count",
    ) { b ->
      when {
        b in 1..3 ->
          Text(b.toString(), style = Theme.type.numeralHero, color = Theme.colors.content)
        b == 0 && go != null ->
          Text(
            go.uppercase(),
            style = Theme.type.hero,
            color = Theme.colors.content,
            modifier =
              Modifier.graphicsLayer {
                scaleX = 1.3f
                scaleY = 1.3f
              },
          )
        else -> Spacer(Modifier.size(1.dp))
      }
    }
  }
}
