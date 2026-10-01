package xyz.mcxross.formation.platform

enum class SoundCue(val file: String) {
  ASSEMBLED("assembled.wav"),
  BEGIN("begin.wav"),
  COMPLETE("complete.wav"),
  REWARD("reward.wav"),
}

/** Called on the main dispatcher. Drop unavailable cues rather than queueing them. */
interface SoundPlayer {
  suspend fun load(cue: SoundCue, bytes: ByteArray)

  fun play(cue: SoundCue)

  fun stop()
}
