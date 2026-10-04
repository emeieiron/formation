package xyz.mcxross.formation.platform

enum class SoundCue(val file: String) {
  // Prepare the first-use story before game cues so cold launches can hear its opening.
  STORY_WAKE("story-wake.wav"),
  STORY_JOIN("story-join.wav"),
  STORY_UNLOCK("story-unlock.wav"),
  STORY_BEAT("story-beat.wav"),
  STORY_COMPLETE("story-complete.wav"),
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
  CHARGE("charge.wav"),
  PIERCE("pierce.wav"),
}

/** Called on the main dispatcher. Drop unavailable cues rather than queueing them. */
interface SoundPlayer {
  suspend fun load(cue: SoundCue, bytes: ByteArray)

  fun play(cue: SoundCue)

  fun stop()
}
