package xyz.mcxross.formation.platform

enum class SoundCue(val file: String) {
  ASSEMBLED("assembled.wav"),
  BEGIN("begin.wav"),
  COMPLETE("complete.wav"),
  REWARD("reward.wav"),
  RETURN("return.wav"),
  FAST_RETURN("fast-return.wav"),
  CLOSE_CALL("close-call.wav"),
  TARGET("target.wav"),
  MISS("miss.wav"),
  DANGER("danger.wav"),
}

/** Called on the main dispatcher. Drop unavailable cues rather than queueing them. */
interface SoundPlayer {
  suspend fun load(cue: SoundCue, bytes: ByteArray)

  fun play(cue: SoundCue)

  fun stop()
}
