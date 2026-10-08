package xyz.mcxross.formation.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.mcxross.formation.BuildConfig

/** Owned by the application; retains no Activity and decodes each cue once. */
internal class AndroidSoundPlayer(context: Context) : SoundPlayer {
  private val cache = File(context.cacheDir, "sound-effects")
  private val audio = context.getSystemService(AudioManager::class.java)
  private val samples = mutableMapOf<SoundCue, Int>()
  private val loaded = mutableSetOf<Int>()
  private var stream = 0
  private var foreground = false
  private val pool =
    SoundPool.Builder()
      .setMaxStreams(1)
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_GAME)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build()
      )
      .build()
      .apply {
        setOnLoadCompleteListener { _, sample, status ->
          if (status == 0) loaded.add(sample)
          if (BuildConfig.DEBUG) {
            val cue = samples.entries.firstOrNull { it.value == sample }?.key
            Log.d("FormationSound", "Load $cue: $status")
          }
        }
      }

  override suspend fun load(cue: SoundCue, bytes: ByteArray) {
    val file =
      withContext(Dispatchers.IO) {
        cache.mkdirs()
        File(cache, cue.file).apply { writeBytes(bytes) }
      }
    samples[cue] = pool.load(file.path, 1)
  }

  override fun play(cue: SoundCue) {
    if (
      !foreground ||
        audio.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
        audio.mode != AudioManager.MODE_NORMAL ||
        audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
    )
      return
    val sample = samples[cue]?.takeIf { it in loaded } ?: return
    stop()
    stream = pool.play(sample, 0.65f, 0.65f, 1, 0, 1f)
    if (BuildConfig.DEBUG) Log.d("FormationSound", "Play $cue: ${stream != 0}")
  }

  override fun stop() {
    if (stream != 0) pool.stop(stream)
    stream = 0
  }

  fun setForeground(active: Boolean) {
    foreground = active
    if (!active) stop()
  }
}
