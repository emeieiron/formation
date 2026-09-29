package xyz.mcxross.formation.design.tokens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import xyz.mcxross.formation.design.resources.Res
import xyz.mcxross.formation.design.resources.inter_text_medium
import xyz.mcxross.formation.design.resources.inter_text_regular
import xyz.mcxross.formation.design.resources.inter_text_semibold
import xyz.mcxross.formation.design.resources.space_grotesk_bold
import xyz.mcxross.formation.design.resources.space_grotesk_light
import xyz.mcxross.formation.design.resources.space_grotesk_medium
import xyz.mcxross.formation.design.resources.space_grotesk_regular

@Immutable
class Typography(
  val hero: TextStyle,
  val display: TextStyle,
  val title1: TextStyle,
  val title2: TextStyle,
  val title3: TextStyle,
  val headline: TextStyle,
  val body: TextStyle,
  val bodyStrong: TextStyle,
  val callout: TextStyle,
  val subhead: TextStyle,
  val subheadStrong: TextStyle,
  val footnote: TextStyle,
  val caption: TextStyle,
  val overline: TextStyle,
  val button: TextStyle,
  val buttonSmall: TextStyle,
  val numeralHero: TextStyle,
  val numeralLarge: TextStyle,
  val numeral: TextStyle,
  val numeralSmall: TextStyle,
  val code: TextStyle,
)

private val Centered = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

@Composable
internal fun rememberTypography(): Typography {
  val grotesk =
    FontFamily(
      Font(Res.font.space_grotesk_light, FontWeight.Light),
      Font(Res.font.space_grotesk_regular, FontWeight.Normal),
      Font(Res.font.space_grotesk_medium, FontWeight.Medium),
      Font(Res.font.space_grotesk_bold, FontWeight.Bold),
    )
  val inter =
    FontFamily(
      Font(Res.font.inter_text_regular, FontWeight.Normal),
      Font(Res.font.inter_text_medium, FontWeight.Medium),
      Font(Res.font.inter_text_semibold, FontWeight.SemiBold),
    )

  fun style(
    family: FontFamily,
    weight: FontWeight,
    size: Int,
    line: Int,
    tracking: Double,
    features: String? = null,
  ) =
    TextStyle(
      fontFamily = family,
      fontWeight = weight,
      fontSize = size.sp,
      lineHeight = line.sp,
      letterSpacing = tracking.em,
      fontFeatureSettings = features,
      lineHeightStyle = Centered,
    )

  return Typography(
    hero = style(grotesk, FontWeight.Bold, 44, 48, -0.035),
    display = style(grotesk, FontWeight.Bold, 34, 38, -0.03),
    title1 = style(grotesk, FontWeight.Bold, 28, 32, -0.025),
    title2 = style(grotesk, FontWeight.Medium, 22, 28, -0.015),
    title3 = style(grotesk, FontWeight.Medium, 18, 24, -0.01),
    headline = style(inter, FontWeight.SemiBold, 17, 22, -0.012),
    body = style(inter, FontWeight.Normal, 16, 24, -0.006),
    bodyStrong = style(inter, FontWeight.Medium, 16, 24, -0.006),
    callout = style(inter, FontWeight.Normal, 15, 21, -0.004),
    subhead = style(inter, FontWeight.Normal, 14, 20, -0.002),
    subheadStrong = style(inter, FontWeight.Medium, 14, 20, -0.002),
    footnote = style(inter, FontWeight.Normal, 13, 18, 0.0),
    caption = style(inter, FontWeight.Medium, 12, 16, 0.0),
    overline = style(grotesk, FontWeight.Medium, 11, 14, 0.14),
    button = style(inter, FontWeight.SemiBold, 16, 20, -0.006),
    buttonSmall = style(inter, FontWeight.SemiBold, 14, 18, -0.002),
    numeralHero = style(grotesk, FontWeight.Light, 96, 96, -0.05, "tnum"),
    numeralLarge = style(grotesk, FontWeight.Medium, 48, 52, -0.035, "tnum"),
    numeral = style(grotesk, FontWeight.Medium, 28, 32, -0.02, "tnum"),
    numeralSmall = style(grotesk, FontWeight.Medium, 17, 22, -0.01, "tnum"),
    code = style(grotesk, FontWeight.Medium, 14, 18, 0.12, "tnum"),
  )
}
