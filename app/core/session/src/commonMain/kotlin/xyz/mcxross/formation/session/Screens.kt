package xyz.mcxross.formation.session

import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable

@Serializable
data class ScreenInsets(val left: Double = 0.0, val top: Double = 0.0, val right: Double = 0.0, val bottom: Double = 0.0)

// A phone's full-screen game window, held in portrait and measured in millimetres. The insets cover
// display cutouts and rounded corners.
@Serializable
data class ScreenProfile(
  val widthMm: Double,
  val heightMm: Double,
  val pxPerMm: Double,
  val insets: ScreenInsets = ScreenInsets(),
  val calibrated: Boolean = false,
) {
  val usableWidthMm: Double get() = widthMm - insets.left - insets.right
  val usableHeightMm: Double get() = heightMm - insets.top - insets.bottom

  // Bounds a phone or tablet can report; anything else is a misreading, not a screen.
  val plausible: Boolean
    get() {
      val edges = listOf(insets.left, insets.top, insets.right, insets.bottom)
      return listOf(widthMm, heightMm, pxPerMm).all { it.isFinite() } && edges.all { it.isFinite() && it >= 0.0 } &&
        pxPerMm in MIN_PX_PER_MM..MAX_PX_PER_MM && widthMm in MIN_SIDE_MM..MAX_SIDE_MM &&
        heightMm in MIN_SIDE_MM..MAX_SIDE_MM && usableWidthMm > widthMm / 2 && usableHeightMm > heightMm / 2
    }

  companion object {
    const val MIN_PX_PER_MM = 3.0
    const val MAX_PX_PER_MM = 40.0
    const val MIN_SIDE_MM = 30.0
    const val MAX_SIDE_MM = 400.0
  }
}

// A game that draws at physical scale declares the smallest usable screen it accepts.
data class ScreenRequirement(val minShortMm: Double, val minLongMm: Double) {
  fun accepts(profile: ScreenProfile?): Boolean {
    if (profile == null || !profile.plausible) return false
    val short = min(profile.usableWidthMm, profile.usableHeightMm)
    val long = max(profile.usableWidthMm, profile.usableHeightMm)
    return short >= minShortMm && long >= minLongMm
  }

  companion object {
    // Phones advertise this capability when their platform can measure the screen's physical size.
    const val CAPABILITY = "display.physical.v1"
  }
}
