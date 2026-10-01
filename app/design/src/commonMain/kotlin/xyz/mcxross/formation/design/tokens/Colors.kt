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
  val onAccent: Color,
  val reward: Color,
  val rewardDeep: Color,
  val rewardHighlight: Color,
  val onReward: Color,
  val positive: Color,
  val warning: Color,
  val negative: Color,
  val scrim: Color,
  val highlight: Color,
  val celebration: List<Color>,
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
      Tone.Reward -> reward
    }

  fun rewardBrush(): Brush = Brush.linearGradient(listOf(rewardHighlight, reward, rewardDeep))
}

@Immutable
class Light(val name: String, val color: Color, val content: Color = Color(0xE6070A10)) {
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
    background = Color(0xFF000000),
    backgroundRaised = Color(0xFF0A0C0F),
    surface = Color(0xFF111418),
    surfaceHigh = Color(0xFF1A1E23),
    surfaceHigher = Color(0xFF23282E),
    line = Color(0xFF292F36),
    lineStrong = Color(0xFF323840),
    content = Color(0xFFF2F3F5),
    contentSecondary = Color(0xFFACB1B8),
    contentTertiary = Color(0xFF858D97),
    contentDisabled = Color(0xFF69717B),
    inverse = Color(0xFFF2F3F5),
    onInverse = Color(0xFF000000),
    accent = Color(0xFFE52629),
    onAccent = Color(0xFFFFFFFF),
    reward = Color(0xFFF2F3F5),
    rewardDeep = Color(0xFFADB3BC),
    rewardHighlight = Color(0xFFFFFFFF),
    onReward = Color(0xFF101216),
    positive = Color(0xFFA2D9B4),
    warning = Color(0xFFFFB547),
    negative = Color(0xFFFF5A5D),
    scrim = Color(0xA8000000),
    highlight = Color.White,
    celebration = listOf(Color(0xFFE52629), Color(0xFFF2F3F5), Color(0xFFACB1B8)),
    lights =
      listOf(
        Light("Ember", Color(0xFFE52629), Color.White),
        Light("Sol", Color(0xFFFFB547)),
        Light("Lime", Color(0xFFB6F24A)),
        Light("Jade", Color(0xFFA2D9B4)),
        Light("Tide", Color(0xFFE8ECEF)),
        Light("Sky", Color(0xFF4D8DFF)),
        Light("Nova", Color(0xFF9B7BFF)),
        Light("Bloom", Color(0xFFFF6FB5)),
      ),
  )
