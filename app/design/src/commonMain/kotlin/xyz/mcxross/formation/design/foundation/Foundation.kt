package xyz.mcxross.formation.design.foundation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import xyz.mcxross.formation.design.LocalContentColor
import xyz.mcxross.formation.design.LocalRaised
import xyz.mcxross.formation.design.LocalTextStyle
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Sizes

@Composable
fun Text(
  text: String,
  modifier: Modifier = Modifier,
  style: TextStyle = LocalTextStyle.current,
  color: Color = Color.Unspecified,
  textAlign: TextAlign? = null,
  maxLines: Int = Int.MAX_VALUE,
  minLines: Int = 1,
  overflow: TextOverflow = TextOverflow.Ellipsis,
  softWrap: Boolean = true,
  onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
  val resolved = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
  BasicText(
    text,
    modifier,
    style.copy(color = resolved, textAlign = textAlign ?: style.textAlign),
    onTextLayout,
    overflow,
    softWrap,
    maxLines,
    minLines,
  )
}

@Composable
fun Text(
  text: AnnotatedString,
  modifier: Modifier = Modifier,
  style: TextStyle = LocalTextStyle.current,
  color: Color = Color.Unspecified,
  textAlign: TextAlign? = null,
  maxLines: Int = Int.MAX_VALUE,
  overflow: TextOverflow = TextOverflow.Ellipsis,
) {
  val resolved = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
  BasicText(
    text,
    modifier,
    style.copy(color = resolved, textAlign = textAlign ?: style.textAlign),
    overflow = overflow,
    maxLines = maxLines,
  )
}

@Composable
fun Icon(
  icon: ImageVector,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  tint: Color = Color.Unspecified,
  size: Dp = Sizes.iconM,
) {
  val color = if (tint.isSpecified) tint else LocalContentColor.current
  Image(
    rememberVectorPainter(icon),
    contentDescription,
    modifier.size(size),
    colorFilter = ColorFilter.tint(color),
  )
}

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Theme.colors.line) {
  Box(modifier.fillMaxWidth().height(Sizes.hairline).drawBehind { drawRect(color) })
}

@Composable
fun Panel(
  modifier: Modifier = Modifier,
  shape: Shape = Shapes.card,
  color: Color = if (LocalRaised.current) Theme.colors.surfaceHigh else Theme.colors.surface,
  border: Color? = Theme.colors.line,
  glow: Color? = null,
  content: @Composable BoxScope.() -> Unit,
) {
  Box(
    modifier
      .clip(shape)
      .background(color)
      .drawBehind {
        drawRect(
          Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.035f),
            0.45f to Color.Transparent,
          )
        )
        if (glow != null) {
          drawRect(
            Brush.radialGradient(
              listOf(glow.copy(alpha = 0.22f), Color.Transparent),
              center = Offset(size.width * 0.12f, 0f),
              radius = size.maxDimension * 0.75f,
            )
          )
        }
      }
      .then(if (border != null) Modifier.border(Sizes.hairline, border, shape) else Modifier),
    content = content,
  )
}

object GlowIndication : IndicationNodeFactory {
  override fun create(interactionSource: InteractionSource): DelegatableNode =
    GlowNode(interactionSource)

  override fun equals(other: Any?): Boolean = other === this

  override fun hashCode(): Int = 4_217
}

private class GlowNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
  private val level = Animatable(0f)

  override fun onAttach() {
    coroutineScope.launch {
      var pressed = 0
      source.interactions.collect { interaction ->
        when (interaction) {
          is PressInteraction.Press -> pressed++
          is PressInteraction.Release,
          is PressInteraction.Cancel -> pressed = (pressed - 1).coerceAtLeast(0)
          else -> return@collect
        }
        launch { level.animateTo(if (pressed > 0) 1f else 0f, tween(if (pressed > 0) 60 else 280)) }
      }
    }
  }

  override fun ContentDrawScope.draw() {
    drawContent()
    if (level.value > 0f) drawRect(Color.White.copy(alpha = 0.07f * level.value))
  }
}

fun Modifier.pressable(
  onClick: () -> Unit,
  enabled: Boolean = true,
  shape: Shape? = null,
  role: Role? = Role.Button,
  squeeze: Boolean = false,
  onClickLabel: String? = null,
): Modifier = composed {
  val source = remember { MutableInteractionSource() }
  val pressed by source.collectIsPressedAsState()
  val scale by
    animateFloatAsState(
      if (squeeze && pressed) 0.965f else 1f,
      spring(dampingRatio = 0.7f, stiffness = 1_300f),
      label = "press",
    )
  (if (squeeze)
      Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
      }
    else Modifier)
    .then(if (shape != null) Modifier.clip(shape) else Modifier)
    .clickable(
      interactionSource = source,
      indication = GlowIndication,
      enabled = enabled,
      onClickLabel = onClickLabel,
      role = role,
      onClick = onClick,
    )
}
