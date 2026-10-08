package xyz.mcxross.formation.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.mcxross.formation.platform.SoundCue

class StoryPlaybackTest {
  @Test
  fun cueCrossingsFireOnceAndSeekingDoesNotReplayEarlierAudio() {
    val playback = StoryPlayback()
    assertTrue(playback.advance(.65f).isEmpty())
    assertEquals(listOf(SoundCue.STORY_WAKE), playback.advance(.06f))
    assertTrue(playback.advance(.02f).isEmpty())
    playback.seek(3, reduced = false)
    assertTrue(playback.advance(.45f).isEmpty())
    assertEquals(listOf(SoundCue.STORY_BEAT), playback.advance(.07f))
  }

  @Test
  fun interruptedFramesDropStaleCuesAndCompletionHoldsItsLastPose() {
    val playback = StoryPlayback()
    assertTrue(playback.advance(17.5f).isEmpty())
    assertEquals(4, playback.chapter)
    playback.advance(20f)
    assertEquals(24f, playback.position)
    assertEquals(5, playback.chapter)
    assertFalse(playback.playing)
    assertTrue(playback.advance(1f).isEmpty())
  }

  @Test
  fun reducedMotionChaptersUseSettledPosesAndWaitForManualNavigation() {
    val playback = StoryPlayback()
    for (chapter in 0..5) {
      playback.seek(chapter, reduced = true)
      assertEquals(chapter, playback.chapter)
      assertEquals(StoryTimeline.still(chapter), playback.position)
      assertFalse(playback.playing)
      assertTrue(playback.advance(10f).isEmpty())
      assertEquals(StoryTimeline.still(chapter), playback.position)
    }
    playback.seek(0, reduced = false)
    assertEquals(0f, playback.position)
    assertTrue(playback.playing)
  }
}
