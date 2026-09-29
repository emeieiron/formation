package xyz.mcxross.formation.design.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.design.LocalContentColor
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.foundation.pressable
import xyz.mcxross.formation.design.tokens.Motion
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Sizes
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.design.tokens.Tone

enum class ButtonStyle {
  Primary,
  Secondary,
  Ghost,
  Reward,
  Aurora,
  Destructive,
}

enum class ButtonSize(val height: Dp, val padding: Dp, val icon: Dp) {
  Large(Sizes.controlLarge, 24.dp, 20.dp),
  Medium(Sizes.controlMedium, 20.dp, 18.dp),
  Small(Sizes.controlSmall, 14.dp, 16.dp),
}

@Composable
fun Button(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  style: ButtonStyle = ButtonStyle.Primary,
  size: ButtonSize = ButtonSize.Large,
  leadingIcon: ImageVector? = null,
  trailingIcon: ImageVector? = null,
  enabled: Boolean = true,
  loading: Boolean = false,
  fillWidth: Boolean = size == ButtonSize.Large,
) {
  val c = Theme.colors
  val live = enabled && !loading
  val fill: Brush? =
    when {
      !enabled -> null
      style == ButtonStyle.Reward -> c.goldBrush()
      style == ButtonStyle.Aurora -> c.auroraBrush()
      else -> null
    }
  val solid by
    animateColorAsState(
      when {
        !enabled && style != ButtonStyle.Ghost -> c.surfaceHigh
        style == ButtonStyle.Primary -> c.inverse
        style == ButtonStyle.Secondary -> c.surfaceHigher
        style == ButtonStyle.Destructive -> c.negative
        else -> Color.Transparent
      },
      Motion.standard(),
      label = "button",
    )
  val content =
    when {
      !enabled -> c.contentDisabled
      style == ButtonStyle.Primary -> c.onInverse
      style == ButtonStyle.Reward -> c.onGold
      style == ButtonStyle.Aurora -> c.onInverse
      style == ButtonStyle.Destructive -> Color.White
      else -> c.content
    }
  val shape = if (size == ButtonSize.Small) Shapes.pill else Shapes.control
  Box(
    modifier
      .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
      .then(if (style == ButtonStyle.Aurora && enabled) Modifier.auroraGlow() else Modifier)
      .defaultMinSize(minWidth = size.height)
      .height(size.height)
      .pressable(onClick, enabled = live, shape = shape, squeeze = true)
      .background(solid)
      .then(if (fill != null) Modifier.background(fill) else Modifier)
      .then(
        if (style == ButtonStyle.Ghost) Modifier.border(Sizes.hairline, c.lineStrong, shape)
        else Modifier
      )
      .padding(horizontal = size.padding),
    contentAlignment = Alignment.Center,
  ) {
    CompositionLocalProvider(LocalContentColor provides content) {
      Row(
        Modifier.alpha(if (loading) 0f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
      ) {
        leadingIcon?.let { Icon(it, null, size = size.icon) }
        Text(
          text,
          style = if (size == ButtonSize.Small) Theme.type.buttonSmall else Theme.type.button,
          maxLines = 1,
          textAlign = TextAlign.Center,
        )
        trailingIcon?.let { Icon(it, null, size = size.icon) }
      }
      if (loading) Spinner(size.icon, color = content)
    }
  }
}

fun Modifier.auroraGlow(): Modifier = composed {
  val colors = Theme.colors.aurora
  val breath by
    rememberInfiniteTransition(label = "glow")
      .animateFloat(
        0.55f,
        1f,
        infiniteRepeatable(tween(1_800, easing = Motion.standard), RepeatMode.Reverse),
        label = "breath",
      )
  drawBehind {
    val spread = 18.dp.toPx()
    val r = size.height * 1.1f
    colors.forEachIndexed { i, color ->
      val x = size.width * (0.2f + 0.3f * i)
      drawOval(
        Brush.radialGradient(
          listOf(color.copy(alpha = 0.34f * breath), Color.Transparent),
          center = Offset(x, size.height / 2),
          radius = r,
        ),
        topLeft = Offset(x - r, -spread),
        size = Size(r * 2, size.height + spread * 2),
      )
    }
  }
}

@Composable
fun TextButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  tone: Tone = Tone.Neutral,
  enabled: Boolean = true,
  icon: ImageVector? = null,
) {
  val c = Theme.colors
  val color =
    if (!enabled) c.contentDisabled else if (tone == Tone.Neutral) c.content else c.tone(tone)
  Row(
    modifier
      .pressable(onClick, enabled = enabled, shape = Shapes.pill)
      .padding(horizontal = Space.m, vertical = Space.s),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Space.xs),
  ) {
    icon?.let { Icon(it, null, tint = color, size = Sizes.iconS) }
    Text(text, style = Theme.type.subheadStrong, color = color, maxLines = 1)
  }
}

enum class IconButtonStyle {
  Filled,
  Ghost,
  Outline,
  Inverse,
}

@Composable
fun IconButton(
  icon: ImageVector,
  contentDescription: String?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  style: IconButtonStyle = IconButtonStyle.Filled,
  size: Dp = 44.dp,
  tint: Color = Color.Unspecified,
  enabled: Boolean = true,
) {
  val c = Theme.colors
  val (background, content) =
    when (style) {
      IconButtonStyle.Filled -> c.surfaceHigh to c.content
      IconButtonStyle.Ghost -> Color.Transparent to c.content
      IconButtonStyle.Outline -> Color.Transparent to c.content
      IconButtonStyle.Inverse -> c.inverse to c.onInverse
    }
  Box(
    modifier
      .size(size)
      .pressable(
        onClick,
        enabled = enabled,
        shape = Shapes.circle,
        squeeze = true,
        onClickLabel = contentDescription,
      )
      .background(background)
      .then(
        if (style == IconButtonStyle.Outline)
          Modifier.border(Sizes.hairline, c.lineStrong, Shapes.circle)
        else Modifier
      ),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      icon,
      contentDescription,
      tint = if (!enabled) c.contentDisabled else if (tint.isSpecified) tint else content,
      size = (size.value * 0.46f).dp,
    )
  }
}

@Composable
fun Chip(
  text: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  accent: Color = Theme.colors.content,
) {
  val c = Theme.colors
  val background by
    animateColorAsState(
      if (selected) accent.copy(alpha = 0.16f) else c.surfaceHigh,
      Motion.standard(),
      label = "chip",
    )
  val border by
    animateColorAsState(
      if (selected) accent.copy(alpha = 0.6f) else c.line,
      Motion.standard(),
      label = "chip-border",
    )
  Row(
    modifier
      .height(36.dp)
      .clip(Shapes.pill)
      .pressable(onClick, shape = Shapes.pill)
      .background(background)
      .border(Sizes.hairline, border, Shapes.pill)
      .padding(horizontal = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    icon?.let {
      Icon(it, null, tint = if (selected) accent else c.contentSecondary, size = Sizes.iconS)
    }
    Text(
      text,
      style = Theme.type.subheadStrong,
      color = if (selected) c.content else c.contentSecondary,
      maxLines = 1,
    )
  }
}
