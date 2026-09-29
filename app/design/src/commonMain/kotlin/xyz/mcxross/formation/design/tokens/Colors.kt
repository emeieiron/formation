package xyz.mcxross.formation.design.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

@Immutable
class Colors(
  val background: Color,
  val backgroundRaised: Color,
  val surface: Color,
  val surfaceHigh: Color,
  val surfaceHigher: Color,
  val line: Color,
  val lineStrong: Color,
  val content: Color,
  val contentSecondary: Color,
  val contentTertiary: Color,
  val contentDisabled: Color,
  val inverse: Color,
  val onInverse: Color,
  val accent: Color,
  val gold: Color,
  val goldDeep: Color,
  val onGold: Color,
  val positive: Color,
  val warning: Color,
  val negative: Color,
  val scrim: Color,
  val lights: List<Light>,
) {
  fun light(index: Int): Light = lights[index.mod(lights.size)]

  fun tone(tone: Tone): Color =
    when (tone) {
      Tone.Neutral -> contentSecondary
      Tone.Accent -> accent
      Tone.Positive -> positive
      Tone.Warning -> warning
      Tone.Negative -> negative
      Tone.Reward -> gold
    }

  val aurora: List<Color> = listOf(Color(0xFF8B7BFF), Color(0xFF4DA3FF), Color(0xFF3DE8B4))

  fun auroraBrush(): Brush = Brush.linearGradient(aurora)

  fun goldBrush(): Brush = Brush.linearGradient(listOf(Color(0xFFFFE08A), gold, goldDeep))
}

@Immutable
class Light(val name: String, val color: Color) {
  val glow: Color
    get() = color.copy(alpha = 0.35f)

  val soft: Color
    get() = color.copy(alpha = 0.14f)
}

enum class Tone {
  Neutral,
  Accent,
  Positive,
  Warning,
  Negative,
  Reward,
}

val FormationColors =
  Colors(
    background = Color(0xFF06070A),
    backgroundRaised = Color(0xFF0A0C11),
    surface = Color(0xFF101218),
    surfaceHigh = Color(0xFF171A22),
    surfaceHigher = Color(0xFF1F232D),
    line = Color(0xFF1C1F27),
    lineStrong = Color(0xFF2C303B),
    content = Color(0xFFF4F5F7),
    contentSecondary = Color(0xFFA2A7B5),
    contentTertiary = Color(0xFF6A7080),
    contentDisabled = Color(0xFF444955),
    inverse = Color(0xFFF4F5F7),
    onInverse = Color(0xFF06070A),
    accent = Color(0xFF9B8CFF),
    gold = Color(0xFFFFC247),
    goldDeep = Color(0xFFFF9B2F),
    onGold = Color(0xFF1A1206),
    positive = Color(0xFF3DDC97),
    warning = Color(0xFFFFB547),
    negative = Color(0xFFFF5A67),
    scrim = Color(0xA8000000),
    lights =
      listOf(
        Light("Ember", Color(0xFFFF6B6B)),
        Light("Sol", Color(0xFFFFB547)),
        Light("Lime", Color(0xFFB6F24A)),
        Light("Jade", Color(0xFF3DDC97)),
        Light("Tide", Color(0xFF36D6E7)),
        Light("Sky", Color(0xFF4D8DFF)),
        Light("Nova", Color(0xFF9B7BFF)),
        Light("Bloom", Color(0xFFFF6FB5)),
      ),
  )
