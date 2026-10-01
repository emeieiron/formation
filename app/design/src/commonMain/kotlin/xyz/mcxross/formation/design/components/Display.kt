package xyz.mcxross.formation.design.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import xyz.mcxross.formation.design.resources.Res
import xyz.mcxross.formation.design.resources.apex_player_plate
import xyz.mcxross.formation.design.resources.apex_reward_pass
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Light
import xyz.mcxross.formation.design.tokens.Motion
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Sizes
import xyz.mcxross.formation.design.tokens.Tone

@Composable
fun PlayerLight(
  name: String,
  light: Light,
  modifier: Modifier = Modifier,
  size: Dp = Sizes.lightM,
  seeker: Boolean = false,
  dimmed: Boolean = false,
  pulse: Boolean = false,
) {
  val glow =
    if (pulse)
      rememberInfiniteTransition(label = "light")
        .animateFloat(
          0.55f,
          1f,
          infiniteRepeatable(tween(1_400, easing = Motion.standard), RepeatMode.Reverse),
          label = "glow",
        )
        .value
    else 0.8f
  Box(
    modifier.size(size).clearAndSetSemantics {
      contentDescription = listOfNotNull(
        name.takeIf { it.isNotBlank() },
        if (seeker) "Seeker" else null,
        if (dimmed) "Disconnected" else null,
      ).joinToString(", ")
    },
    contentAlignment = Alignment.Center,
  ) {
    Image(
      painterResource(Res.drawable.apex_player_plate),
      null,
      Modifier.size(size),
      alpha = if (dimmed) 0.35f else 1f,
      colorFilter = ColorFilter.tint(light.color, BlendMode.Modulate),
    )
    if (pulse) Canvas(Modifier.size(size)) {
      drawRoundRect(
        light.color.copy(alpha = glow),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
        style = Stroke(2.dp.toPx()),
      )
    }
    if (name.isNotBlank()) {
      Text(
        initial(name),
        style =
          Theme.type.title3.copy(
            fontSize = (size.value * 0.42f).sp,
            lineHeight = (size.value * 0.5f).sp,
            fontWeight = FontWeight.Bold,
          ),
        color = light.content,
        maxLines = 1,
      )
    }
    if (seeker) {
      Box(
        Modifier.align(Alignment.TopEnd)
          .size((size.value * 0.36f).dp)
          .clip(Shapes.circle)
          .background(Theme.colors.background)
          .padding(2.dp)
          .clip(Shapes.circle)
          .background(Theme.colors.reward),
        contentAlignment = Alignment.Center,
      ) {
        Icon(Icons.Spark, "Seeker", tint = Theme.colors.onReward, size = (size.value * 0.22f).dp)
      }
    }
  }
}

private fun initial(name: String): String = name.trim().firstOrNull()?.uppercase() ?: "?"

@Composable
fun EmptySlot(modifier: Modifier = Modifier, size: Dp = Sizes.lightM) {
  val c = Theme.colors
  Canvas(modifier.size(size)) {
    val r = this.size.minDimension / 2 * 0.72f
    val dash = floatArrayOf(r * 0.28f, r * 0.24f)
    drawCircle(
      c.contentTertiary.copy(alpha = 0.7f),
      radius = r,
      style =
        Stroke(
          1.4.dp.toPx(),
          pathEffect = PathEffect.dashPathEffect(dash),
        ),
    )
  }
}

@Composable
fun SkrCoin(size: Dp, modifier: Modifier = Modifier, spin: Boolean = false) {
  val c = Theme.colors
  // A slow rock back and forth: alive, but never edge-on.
  val turn =
    if (spin)
      rememberInfiniteTransition(label = "coin")
        .animateFloat(
          -28f,
          28f,
          infiniteRepeatable(tween(1_600, easing = Motion.standard), RepeatMode.Reverse),
          label = "turn",
        )
        .value
    else 0f
  Canvas(
    modifier.size(size).graphicsLayer {
      rotationY = turn
      cameraDistance = 12f * density
    }
  ) {
    val r = this.size.minDimension / 2
    drawCircle(c.rewardBrush(), r)
    drawCircle(c.rewardHighlight.copy(alpha = 0.25f), r * 0.82f, style = Stroke(r * 0.07f))
    val star = Path()
    for (i in 0 until 8) {
      val a = (-90.0 + i * 45.0) * PI / 180
      val rr = if (i % 2 == 0) r * 0.46f else r * 0.13f
      val x = center.x + rr * cos(a).toFloat()
      val y = center.y + rr * sin(a).toFloat()
      if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
    }
    star.close()
    drawPath(star, c.onReward.copy(alpha = 0.78f))
  }
}

@Composable
fun SkrAmount(
  amount: String,
  modifier: Modifier = Modifier,
  style: TextStyle = Theme.type.numeralSmall,
  color: Color = Theme.colors.content,
  unitColor: Color = Theme.colors.contentSecondary,
  coin: Boolean = true,
  unit: Boolean = true,
) {
  val size = style.fontSize.value
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    if (coin) {
      SkrCoin((size * 0.95f).dp)
      Spacer(Modifier.width((size * 0.32f).coerceAtLeast(4f).dp))
    }
    Text(amount, style = style, color = color, maxLines = 1)
    if (unit) {
      Spacer(Modifier.width((size * 0.22f).coerceAtLeast(3f).dp))
      Text(
        "SKR",
        style =
          style.copy(
            fontSize = (size * 0.48f).coerceAtLeast(11f).sp,
            lineHeight = (size * 0.58f).coerceAtLeast(13f).sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.08.sp,
          ),
        color = unitColor,
        maxLines = 1,
      )
    }
  }
}

@Composable
fun RollingNumber(
  text: String,
  modifier: Modifier = Modifier,
  style: TextStyle = Theme.type.numeral,
  color: Color = Color.Unspecified,
) {
  Row(modifier) {
    text.forEachIndexed { i, ch ->
      AnimatedContent(
        ch,
        transitionSpec = {
          val up = targetState > initialState
          (slideInVertically(Motion.standard()) { if (up) it else -it } +
            fadeIn(Motion.standard())) togetherWith
            (slideOutVertically(Motion.standard()) { if (up) -it else it } +
              fadeOut(Motion.standard(Motion.FAST)))
        },
        label = "digit$i",
      ) { c ->
        Text(c.toString(), style = style, color = color)
      }
    }
  }
}

enum class TagStyle {
  Subtle,
  Solid,
  Outline,
}

@Composable
fun Tag(
  text: String,
  modifier: Modifier = Modifier,
  tone: Tone = Tone.Neutral,
  style: TagStyle = TagStyle.Subtle,
  icon: ImageVector? = null,
  color: Color? = null,
) {
  val c = Theme.colors
  val accent = color ?: if (tone == Tone.Neutral) c.contentSecondary else c.tone(tone)
  val (background, content) =
    when (style) {
      TagStyle.Solid -> accent to when (tone) {
        Tone.Negative -> c.highlight
        Tone.Reward -> c.onReward
        else -> c.onAccent
      }
      TagStyle.Subtle ->
        (if (tone == Tone.Neutral && color == null) c.surfaceHigher
        else accent.copy(alpha = 0.15f)) to
          (if (tone == Tone.Neutral && color == null) c.contentSecondary else accent)
      TagStyle.Outline -> Color.Transparent to accent
    }
  Row(
    modifier
      .height(24.dp)
      .clip(Shapes.tag)
      .background(background)
      .then(
        if (style == TagStyle.Outline)
          Modifier.border(Sizes.hairline, accent.copy(alpha = 0.5f), Shapes.tag)
        else Modifier
      )
      .padding(horizontal = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    icon?.let { Icon(it, null, tint = content, size = 12.dp) }
    Text(text.uppercase(), style = Theme.type.overline, color = content, maxLines = 1)
  }
}

@Composable
fun Stat(
  value: String,
  label: String,
  modifier: Modifier = Modifier,
  valueStyle: TextStyle = Theme.type.numeral,
  valueColor: Color = Color.Unspecified,
  horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
  Column(modifier, horizontalAlignment = horizontalAlignment) {
    Text(value, style = valueStyle, color = valueColor, maxLines = 1)
    Spacer(Modifier.height(2.dp))
    Text(label, style = Theme.type.footnote, color = Theme.colors.contentSecondary, maxLines = 1)
  }
}

@Composable
fun Overline(
  text: String,
  modifier: Modifier = Modifier,
  color: Color = Theme.colors.contentTertiary,
) {
  Text(text.uppercase(), modifier, style = Theme.type.overline, color = color, maxLines = 1)
}

@Composable
fun IconTile(
  icon: ImageVector,
  modifier: Modifier = Modifier,
  tint: Color = Theme.colors.content,
  size: Dp = 40.dp,
  background: Color = tint.copy(alpha = 0.12f),
) {
  Box(
    modifier.size(size).clip(Shapes.tile).background(background),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, null, tint = tint, size = (size.value * 0.5f).dp)
  }
}

@Composable
fun VerticalRule(modifier: Modifier = Modifier, height: Dp = 32.dp) {
  val line = Theme.colors.line
  Box(
    modifier.width(Sizes.hairline).height(height).drawBehind {
      drawRect(line)
    }
  )
}

/** Decorative pass; amounts and claim state remain live UI text. */
@Composable
fun RewardPass(modifier: Modifier = Modifier) {
  Image(painterResource(Res.drawable.apex_reward_pass), null, modifier)
}
