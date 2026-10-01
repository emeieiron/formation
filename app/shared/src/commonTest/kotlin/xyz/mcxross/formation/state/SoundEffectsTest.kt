package xyz.mcxross.formation.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.platform.SoundCue
import xyz.mcxross.formation.platform.SoundPlayer

@OptIn(ExperimentalCoroutinesApi::class)
class SoundEffectsTest {
  private class Store : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun get(key: String) = values[key]
    override fun put(key: String, value: String?) {
      if (value == null) values.remove(key) else values[key] = value
    }
  }

  private class Player : SoundPlayer {
    val played = mutableListOf<SoundCue>()
    var stops = 0
    var unavailable = false
    override suspend fun load(cue: SoundCue, bytes: ByteArray) {}
    override fun play(cue: SoundCue) {
      check(!unavailable)
      played += cue
    }
    override fun stop() { stops++ }
  }

  @Test
  fun mutingStopsPlaybackAndPersistsAcrossLaunches() = runTest {
    val store = Store()
    val player = Player()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val effects = SoundEffects(store, player, backgroundScope, dispatcher)
    assertTrue(effects.enabled.value)
    effects.play(SoundCue.COMPLETE)
    runCurrent()
    assertEquals(listOf(SoundCue.COMPLETE), player.played)

    effects.setEnabled(false)
    effects.play(SoundCue.REWARD)
    runCurrent()
    assertEquals(1, player.stops)
    assertEquals(listOf(SoundCue.COMPLETE), player.played)
    assertFalse(SoundEffects(store, Player(), backgroundScope, dispatcher).enabled.value)

    effects.setEnabled(true)
    effects.play(SoundCue.REWARD)
    runCurrent()
    assertEquals(listOf(SoundCue.COMPLETE, SoundCue.REWARD), player.played)
    assertTrue(SoundEffects(store, Player(), backgroundScope, dispatcher).enabled.value)
  }

  @Test
  fun aQueuedRequestDoesNotPlayAfterMute() = runTest {
    val player = Player()
    val effects = SoundEffects(Store(), player, backgroundScope, StandardTestDispatcher(testScheduler))
    effects.play(SoundCue.BEGIN)
    effects.setEnabled(false)
    runCurrent()
    assertTrue(player.played.isEmpty())
  }

  @Test
  fun unavailablePlaybackDoesNotBreakLaterFeedback() = runTest {
    val player = Player()
    val effects = SoundEffects(Store(), player, backgroundScope, StandardTestDispatcher(testScheduler))
    player.unavailable = true
    effects.play(SoundCue.COMPLETE)
    runCurrent()
    player.unavailable = false
    effects.play(SoundCue.REWARD)
    runCurrent()
    assertEquals(listOf(SoundCue.REWARD), player.played)
  }
}
