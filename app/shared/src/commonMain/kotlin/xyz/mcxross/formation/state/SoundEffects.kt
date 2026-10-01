package xyz.mcxross.formation.state

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.platform.SoundCue
import xyz.mcxross.formation.platform.SoundPlayer
import xyz.mcxross.formation.resources.Res

class SoundEffects(
  private val store: KeyValueStore,
  private val player: SoundPlayer,
  private val scope: CoroutineScope,
  private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
  private val _enabled = MutableStateFlow(store.get(KEY_ENABLED) != "false")
  val enabled = _enabled.asStateFlow()

  suspend fun prepare() {
    SoundCue.entries.forEach { cue ->
      // Audio failure must never prevent joining, playing, or receiving a reward.
      try {
        val bytes = Res.readBytes("files/sounds/${cue.file}")
        withContext(dispatcher) { player.load(cue, bytes) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // A missing or unsupported optional cue remains silent.
      }
    }
  }

  fun setEnabled(on: Boolean) {
    _enabled.value = on
    store.put(KEY_ENABLED, on.toString())
    if (!on) scope.launch(dispatcher) { runCatching { player.stop() } }
  }

  fun play(cue: SoundCue) {
    scope.launch(dispatcher) {
      // Check at playback time as well: muting can happen while a request is dispatched.
      if (_enabled.value) runCatching { player.play(cue) }
    }
  }

  private companion object {
    const val KEY_ENABLED = "sound.enabled"
  }
}
