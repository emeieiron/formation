package xyz.mcxross.formation.overdrive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.challenge.PlayerView
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.PlayerLight
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.icons.Icons

@Composable
internal fun PartnerClue(
  player: PlayerView,
  dial: Dial,
  wave: Int,
  preview: Boolean,
  now: State<Long>,
) {
  val symbol = dial.clue ?: return
  val colors = Theme.colors
  val pop = remember { Animatable(1f) }
  OnChange(wave) { _, _ ->
    pop.snapTo(1.35f)
    pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 700f))
  }
  Row(
    Modifier.fillMaxWidth()
      .border(1.dp, colors.lineStrong, RoundedCornerShape(8.dp))
      .clearAndSetSemantics {
        contentDescription =
          "Wave $wave. Clue for ${player.name}: ${symbol.label}" +
            if (preview && dial.nextClue != null) ". Next: ${dial.nextClue.label}" else ""
      }
      .padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // The partner's remaining fall drains around their light, so the caller sees how urgent the
    // call is.
    Box(
      Modifier.size(48.dp).drawBehind {
        if (dial.result != Catch.Pending) return@drawBehind
        val left = 1f - dial.progress(now.value)
        val stroke = 3.dp.toPx()
        val corner = Offset(stroke / 2, stroke / 2)
        val ring = Size(size.width - stroke, size.height - stroke)
        drawArc(colors.line, 0f, 360f, false, corner, ring, style = Stroke(stroke))
        drawArc(
          when {
            left < 0.2f -> colors.negative
            left < 0.45f -> colors.warning
            else -> colors.contentSecondary
          },
          -90f,
          360f * left,
          false,
          corner,
          ring,
          style = Stroke(stroke, cap = StrokeCap.Round),
        )
      },
      contentAlignment = Alignment.Center,
    ) {
      PlayerLight(player.name, player.light, size = 38.dp)
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text("CALL TO", style = Theme.type.overline, color = colors.contentTertiary)
      Text(player.name, style = Theme.type.title3, maxLines = 1)
    }
    SymbolGlyph(
      symbol,
      Modifier.size(44.dp).graphicsLayer {
        scaleX = pop.value
        scaleY = pop.value
      },
    )
    if (preview && dial.nextClue != null) {
      Icon(Icons.ChevronRight, null, tint = colors.contentTertiary, size = 12.dp)
      SymbolGlyph(dial.nextClue, Modifier.size(22.dp))
    }
  }
}
