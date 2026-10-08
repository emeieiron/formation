package xyz.mcxross.formation.platform

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.RoundedCorner
import android.view.WindowManager
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.mcxross.formation.session.ScreenInsets
import xyz.mcxross.formation.session.ScreenProfile

// Measures the full-screen game window in millimetres. Call [update] on the main thread whenever
// the
// activity's window or configuration may have changed.
class AndroidScreen(context: Context, private val activity: () -> Activity?) : ScreenPort {
  private val prefs = context.getSharedPreferences("formation.screen", Context.MODE_PRIVATE)
  private val state = MutableStateFlow<ScreenMeasurement>(ScreenMeasurement.NotFullScreen)

  override val measurable = true
  override val measurement: StateFlow<ScreenMeasurement> = state.asStateFlow()

  override fun calibrate(pxPerMm: Double?) {
    prefs.edit {
      if (pxPerMm == null) remove(KEY_CALIBRATION) else putFloat(KEY_CALIBRATION, pxPerMm.toFloat())
    }
    activity()?.let { it.runOnUiThread { update(it) } }
  }

  // Counted, because a phase transition briefly composes the outgoing stage alongside the incoming
  // one, and
  // the outgoing copy leaving must not restore the bars under the live stage.
  private var fullScreenHolds = 0

  override fun fullScreen(on: Boolean) {
    val current = activity() ?: return
    current.runOnUiThread {
      fullScreenHolds = (fullScreenHolds + if (on) 1 else -1).coerceAtLeast(0)
      val held = fullScreenHolds > 0
      val window = current.window
      val controller = WindowCompat.getInsetsController(window, window.decorView)
      if (held) {
        controller.systemBarsBehavior =
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
      } else controller.show(WindowInsetsCompat.Type.systemBars())
      // Neighbouring phones match more closely at a shared brightness than at each owner's setting.
      window.attributes =
        window.attributes.apply {
          screenBrightness =
            if (held) STAGE_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }
  }

  fun update(activity: Activity) {
    state.value = measure(activity)
  }

  private fun measure(activity: Activity): ScreenMeasurement {
    if (activity.isInMultiWindowMode) return ScreenMeasurement.NotFullScreen
    val bounds = fullBounds(activity)
    val metrics = activity.resources.displayMetrics
    val reported = (metrics.xdpi + metrics.ydpi) / 2.0 / MM_PER_INCH
    val calibrated = prefs.getFloat(KEY_CALIBRATION, 0f).toDouble().takeIf { it > 0.0 }
    val pxPerMm = calibrated ?: reported
    val corners = cornerInsets(activity)
    val cutout = cutoutInsets(activity)
    val px =
      Rect(
        max(corners.left, cutout.left),
        max(corners.top, cutout.top),
        max(corners.right, cutout.right),
        max(corners.bottom, cutout.bottom),
      )
    val profile =
      ScreenProfile(
        widthMm = bounds.width() / pxPerMm,
        heightMm = bounds.height() / pxPerMm,
        pxPerMm = pxPerMm,
        insets =
          ScreenInsets(
            px.left / pxPerMm,
            px.top / pxPerMm,
            px.right / pxPerMm,
            px.bottom / pxPerMm,
          ),
        calibrated = calibrated != null,
      )
    if (calibrated != null)
      return if (profile.plausible) ScreenMeasurement.Measured(profile)
      else ScreenMeasurement.NeedsCalibration(profile)
    // Some devices report a placeholder density. Square pixels near the density bucket and a
    // phone-sized
    // or tablet-sized diagonal suggest the value is real.
    val square = abs(metrics.xdpi - metrics.ydpi) <= 0.05f * max(metrics.xdpi, metrics.ydpi)
    val nearBucket = abs(reported * MM_PER_INCH - metrics.densityDpi) <= 0.35 * metrics.densityDpi
    val inches = hypot(profile.widthMm, profile.heightMm) / MM_PER_INCH
    val trusted = square && nearBucket && inches in MIN_DIAGONAL..MAX_DIAGONAL && profile.plausible
    return if (trusted) ScreenMeasurement.Measured(profile)
    else ScreenMeasurement.NeedsCalibration(profile)
  }

  private fun fullBounds(activity: Activity): Rect =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
      activity.windowManager.maximumWindowMetrics.bounds
    else
      DisplayMetrics().let {
        @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.getRealMetrics(it)
        Rect(0, 0, it.widthPixels, it.heightPixels)
      }

  private fun cutoutInsets(activity: Activity): Rect {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return Rect()
    val cutout = activity.window.decorView.rootWindowInsets?.displayCutout ?: return Rect()
    return Rect(
      cutout.safeInsetLeft,
      cutout.safeInsetTop,
      cutout.safeInsetRight,
      cutout.safeInsetBottom,
    )
  }

  // A rectangle inset by r(1 - 1/sqrt 2) on both axes clears a corner of radius r.
  private fun cornerInsets(activity: Activity): Rect {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      val fallback =
        (FALLBACK_CORNER_MM * activity.resources.displayMetrics.xdpi / MM_PER_INCH *
            CORNER_CLEARANCE)
          .toInt()
      return Rect(fallback, fallback, fallback, fallback)
    }
    val display = activity.display ?: return Rect()
    fun clear(position: Int) =
      ((display.getRoundedCorner(position)?.radius ?: 0) * CORNER_CLEARANCE).toInt()
    val topLeft = clear(RoundedCorner.POSITION_TOP_LEFT)
    val topRight = clear(RoundedCorner.POSITION_TOP_RIGHT)
    val bottomRight = clear(RoundedCorner.POSITION_BOTTOM_RIGHT)
    val bottomLeft = clear(RoundedCorner.POSITION_BOTTOM_LEFT)
    return Rect(
      max(topLeft, bottomLeft),
      max(topLeft, topRight),
      max(topRight, bottomRight),
      max(bottomLeft, bottomRight),
    )
  }

  private companion object {
    const val KEY_CALIBRATION = "px_per_mm"
    const val MM_PER_INCH = 25.4
    const val MIN_DIAGONAL = 3.5
    const val MAX_DIAGONAL = 14.0
    const val FALLBACK_CORNER_MM = 6.0
    const val CORNER_CLEARANCE = 0.2929
    const val STAGE_BRIGHTNESS = 0.8f
  }
}
