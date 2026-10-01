package xyz.mcxross.formation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.PlayerLight
import xyz.mcxross.formation.design.components.RewardPass
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Light
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.resources.Res
import xyz.mcxross.formation.resources.action_claim
import xyz.mcxross.formation.resources.action_join
import xyz.mcxross.formation.resources.label_play

/** Decorative demonstration; these plates never represent current participants or rewards. */
@Composable
internal fun IntroJourney(progress: Float, modifier: Modifier = Modifier) {
  val c = Theme.colors
  val assembly = ((progress - 0.25f) / 0.4f).coerceIn(0f, 1f)
  val receipt = ((progress - 0.65f) / 0.35f).coerceIn(0f, 1f)
  Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
    JourneyStep(stringResource(Res.string.action_join), Modifier.weight(1f)) {
      Icon(Icons.Scan, null, size = 36.dp)
    }
    JourneyStep(stringResource(Res.string.label_play), Modifier.weight(1f)) {
      Canvas(Modifier.fillMaxSize()) {
        val path = Path().apply {
          moveTo(28.dp.toPx(), 18.dp.toPx())
          cubicTo(46.dp.toPx(), 18.dp.toPx(), 26.dp.toPx(), 54.dp.toPx(), 44.dp.toPx(), 54.dp.toPx())
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val reveal = Path()
        measure.getSegment(0f, measure.length * assembly, reveal, true)
        drawPath(reveal, c.accent, style = Stroke(2.dp.toPx()))
      }
      PlayerLight(
        "",
        Light("Preview", c.content, c.onInverse),
        size = 28.dp,
        modifier = Modifier.align(Alignment.TopStart).offset(2.dp, 4.dp).graphicsLayer {
          alpha = assembly
          translationX = (assembly - 1) * 12.dp.toPx()
        },
      )
      PlayerLight(
        "",
        Light("Preview", c.accent, c.onAccent),
        size = 28.dp,
        modifier = Modifier.align(Alignment.BottomEnd).offset((-2).dp, (-4).dp).graphicsLayer {
          alpha = assembly
          translationX = (1 - assembly) * 12.dp.toPx()
        },
      )
    }
    JourneyStep(stringResource(Res.string.action_claim), Modifier.weight(1f)) {
      RewardPass(
        Modifier.size(68.dp, 43.dp).graphicsLayer {
          alpha = receipt
          translationY = (1 - receipt) * 8.dp.toPx()
        }
      )
    }
  }
}

@Composable
private fun JourneyStep(label: String, modifier: Modifier, art: @Composable BoxScope.() -> Unit) {
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
      Modifier.size(72.dp).clip(Shapes.control).background(Theme.colors.surface)
        .border(1.dp, Theme.colors.line, Shapes.control).clearAndSetSemantics {},
      contentAlignment = Alignment.Center,
      content = art,
    )
    Spacer(Modifier.height(Space.m))
    Text(label, style = Theme.type.subheadStrong, textAlign = TextAlign.Center, maxLines = 2)
  }
}
