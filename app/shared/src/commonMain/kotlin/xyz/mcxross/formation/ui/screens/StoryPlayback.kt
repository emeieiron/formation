package xyz.mcxross.formation.ui.screens

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import xyz.mcxross.formation.platform.SoundCue

/** All artwork, captions and cue crossings use this one playhead, in seconds. */
@Stable
internal class StoryPlayback(position: Float = 0f, playing: Boolean = true) {
  var position by mutableFloatStateOf(position)
    private set
  var playing by mutableStateOf(playing)
  val chapter: Int get() = StoryTimeline.chapter(position)

  fun seek(chapter: Int, reduced: Boolean) {
    position = if (reduced) StoryTimeline.still(chapter) else StoryTimeline.starts[chapter]
    playing = !reduced
  }

  fun advance(seconds: Float): List<SoundCue> {
    if (!playing) return emptyList()
    val previous = position
    position = (position + seconds.coerceAtLeast(0f)).coerceAtMost(StoryTimeline.duration)
    if (position == StoryTimeline.duration) playing = false
    // A delayed frame may cross several beats. Drop old cues instead of playing a burst.
    return StoryTimeline.cues.filter { (at, _) -> at > previous && at <= position && position - at < .1f }
      .map { it.second }
  }

  companion object {
    val Saver = listSaver<StoryPlayback, Any>(
      save = { listOf(it.position, it.playing) },
      restore = { StoryPlayback(it[0] as Float, it[1] as Boolean) },
    )
  }
}

internal object StoryTimeline {
  const val duration = 24f
  val starts = listOf(0f, 3.5f, 7.5f, 11.5f, 17.5f, 21.5f)
  fun chapter(time: Float) = starts.indexOfLast { time >= it }.coerceAtLeast(0)
  // Holds avoid capturing transitional poses, especially the catalogue-to-play dissolve.
  fun still(chapter: Int) = listOf(3f, 6.8f, 10.1f, 16.7f, 20.8f, 23.5f)[chapter]
  val cues = listOf(
    .7f to SoundCue.STORY_WAKE,
    4.2f to SoundCue.STORY_JOIN, 4.92f to SoundCue.STORY_JOIN,
    8.9f to SoundCue.STORY_UNLOCK, 10.55f to SoundCue.STORY_WAKE,
    12f to SoundCue.STORY_BEAT, 12.72f to SoundCue.STORY_BEAT,
    13.44f to SoundCue.STORY_BEAT, 14.16f to SoundCue.STORY_BEAT,
    14.88f to SoundCue.STORY_BEAT, 15.6f to SoundCue.STORY_BEAT,
    16.32f to SoundCue.STORY_BEAT,
    18.4f to SoundCue.STORY_COMPLETE, 22f to SoundCue.STORY_WAKE,
  )
}
