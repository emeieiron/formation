package xyz.mcxross.formation.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.challenge.ChallengeInfo
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.IconTile
import xyz.mcxross.formation.design.components.SkrAmount
import xyz.mcxross.formation.design.components.Tag
import xyz.mcxross.formation.design.components.TagStyle
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.design.tokens.Motion
import xyz.mcxross.formation.design.tokens.Tone
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.RewardSplit
import xyz.mcxross.formation.model.Tier
import xyz.mcxross.formation.state.ChallengeCatalog

@Composable
fun ChallengeGlyph(info: ChallengeInfo?, size: Dp = 40.dp) {
  val c = Theme.colors
  val color = info?.let { c.light(it.light).color } ?: c.contentSecondary
  IconTile(info?.icon ?: Icons.Spark, tint = color, size = size)
}

fun challengeInfo(id: ChallengeId): ChallengeInfo? = ChallengeCatalog[id]?.info

@Composable
fun TierTag(tier: Tier, modifier: Modifier = Modifier) {
  val c = Theme.colors
  when (tier) {
    Tier.LEGENDARY ->
      Tag(tier.label, modifier, tone = Tone.Reward, style = TagStyle.Solid, icon = Icons.Sparkles)
    Tier.CROWD -> Tag(tier.label, modifier, color = c.light(6).color)
    Tier.CREW -> Tag(tier.label, modifier, color = c.light(4).color)
    else -> Tag(tier.label, modifier)
  }
}

@Composable
fun RewardSplitView(
  split: RewardSplit,
  modifier: Modifier = Modifier,
  ownerLabel: String = "Seeker owner",
) {
  val c = Theme.colors
  Column(modifier.fillMaxWidth()) {
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
      val total = split.total.units.toFloat().coerceAtLeast(1f)
      val gap = 3.dp.toPx()
      val ownerW = size.width * split.owner.units / total
      drawRoundRect(
        c.reward,
        Offset.Zero,
        Size((ownerW - gap / 2).coerceAtLeast(0f), size.height),
        CornerRadius(size.height / 2),
      )
      val each = (size.width - ownerW) / split.helpers
      for (i in 0 until split.helpers) {
        val x = ownerW + i * each + gap / 2
        drawRoundRect(
          c.contentSecondary,
          Offset(x, 0f),
          Size((each - gap).coerceAtLeast(1f), size.height),
          CornerRadius(size.height / 2),
        )
      }
    }
    Spacer(Modifier.height(Space.m))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
      Column(Modifier.weight(1f)) {
        Text(ownerLabel, style = Theme.type.footnote, color = c.contentSecondary)
        SkrAmount(split.owner.format(2), color = c.reward)
      }
      Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
        Text(
          if (split.helpers == 1) "1 helper" else "Each of ${split.helpers} helpers",
          style = Theme.type.footnote,
          color = c.contentSecondary,
        )
        SkrAmount(split.helper.format(2))
      }
    }
  }
}

@Composable
fun Slots(
  total: Int,
  filled: Int,
  modifier: Modifier = Modifier,
  max: Int = 10,
  color: Color = Theme.colors.content,
  animate: Boolean = false,
) {
  val c = Theme.colors
  Row(
    modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    repeat(minOf(total, max)) { i ->
      val fill by animateFloatAsState(if (i < filled) 1f else 0f,
        if (animate) Motion.standard(280) else tween(0), label = "slot-$i")
      Box(Modifier.size(10.dp)) {
        Canvas(Modifier.size(10.dp)) {
          drawCircle(
              c.contentTertiary,
              style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx()),
            )
          if (fill > 0f) drawCircle(color.copy(alpha = fill), radius = size.minDimension / 2 * fill)
        }
      }
    }
    if (total > max) {
      Spacer(Modifier.width(2.dp))
      Text("+${total - max}", style = Theme.type.caption, color = c.contentSecondary)
    }
  }
}
