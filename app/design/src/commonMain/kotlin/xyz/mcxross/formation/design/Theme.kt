package xyz.mcxross.formation.design

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import xyz.mcxross.formation.design.foundation.GlowIndication
import xyz.mcxross.formation.design.tokens.Colors
import xyz.mcxross.formation.design.tokens.FormationColors
import xyz.mcxross.formation.design.tokens.Typography
import xyz.mcxross.formation.design.tokens.rememberTypography

private val LocalColors = staticCompositionLocalOf { FormationColors }
private val LocalTypography =
  staticCompositionLocalOf<Typography> { error("Wrap the UI in FormationTheme") }

val LocalContentColor = compositionLocalOf { Color.White }
val LocalTextStyle = compositionLocalOf { TextStyle.Default }

val LocalRaised = compositionLocalOf { false }

object Theme {
  val colors: Colors
    @Composable @ReadOnlyComposable get() = LocalColors.current

  val type: Typography
    @Composable @ReadOnlyComposable get() = LocalTypography.current
}

@Composable
fun FormationTheme(content: @Composable () -> Unit) {
  val colors = FormationColors
  val type = rememberTypography()
  val selection =
    remember(colors) {
      TextSelectionColors(
        handleColor = colors.accent,
        backgroundColor = colors.accent.copy(alpha = 0.35f),
      )
    }
  CompositionLocalProvider(
    LocalColors provides colors,
    LocalTypography provides type,
    LocalContentColor provides colors.content,
    LocalTextStyle provides type.body,
    LocalIndication provides GlowIndication,
    LocalTextSelectionColors provides selection,
    content = content,
  )
}
