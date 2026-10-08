package xyz.mcxross.formation.sensors

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class AndroidHaptics(context: Context) : Haptics {
  private val vibrator: Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
      context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

  override fun tick() = play(predefined(VibrationEffect.EFFECT_TICK) ?: oneShot(10, 90))

  override fun confirm() = play(predefined(VibrationEffect.EFFECT_CLICK) ?: oneShot(22, 200))

  override fun reject() = play(waveform(longArrayOf(0, 45, 70, 45), intArrayOf(0, 255, 0, 255)))

  override fun heavy() = play(predefined(VibrationEffect.EFFECT_HEAVY_CLICK) ?: oneShot(55, 255))

  override fun success() =
    play(
      predefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        ?: waveform(longArrayOf(0, 20, 70, 20), intArrayOf(0, 230, 0, 230))
    )

  override fun heartbeat() = play(waveform(longArrayOf(0, 26, 110, 18), intArrayOf(0, 200, 0, 110)))

  private fun predefined(effect: Int): VibrationEffect? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) VibrationEffect.createPredefined(effect)
    else null

  private fun oneShot(ms: Long, amplitude: Int) = VibrationEffect.createOneShot(ms, amplitude)

  private fun waveform(timings: LongArray, amplitudes: IntArray) =
    VibrationEffect.createWaveform(timings, amplitudes, -1)

  private fun play(effect: VibrationEffect) {
    val v = vibrator ?: return
    runCatching { if (v.hasVibrator()) v.vibrate(effect) }
  }
}
