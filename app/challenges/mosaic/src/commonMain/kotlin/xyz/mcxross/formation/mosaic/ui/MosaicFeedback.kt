package xyz.mcxross.formation.mosaic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import xyz.mcxross.formation.challenge.GameCue
import xyz.mcxross.formation.challenge.StageScope
import xyz.mcxross.formation.mosaic.MosaicState
import xyz.mcxross.formation.mosaic.Pinch
import xyz.mcxross.formation.mosaic.SeamOutcome

// A wrong pair costs everyone, so every phone hears it; seals and alignment hints stay with the two
// phones
// involved, which keeps a large group from drowning in cues.
@Composable
internal fun MosaicFeedback(scope: StageScope<MosaicState, Pinch>) {
  LaunchedEffect(scope.round, scope.me) {
    // Events already in the first frame belong to a restored stage and stay quiet.
    var seen = scope.state.events.maxOfOrNull { it.id } ?: 0L
    var beatAt = Long.MIN_VALUE
    while (isActive) {
      val state = scope.state
      val fresh = state.events.filter { it.id > seen }
      if (fresh.isNotEmpty()) {
        seen = fresh.maxOf { it.id }
        val mine = fresh.filter { scope.me in it.players }
        when {
          fresh.any { it.outcome == SeamOutcome.Wrong } -> {
            scope.audio.play(GameCue.Miss)
            scope.haptics.reject()
          }
          mine.any { it.outcome == SeamOutcome.Sealed } -> {
            scope.audio.play(GameCue.Target)
            scope.haptics.success()
          }
          mine.any { it.outcome == SeamOutcome.Misaligned } -> {
            scope.audio.play(GameCue.CloseCall)
            scope.haptics.tick()
          }
        }
      }
      val now = scope.clock.hostNow()
      if (
        state.finishedAt == null && state.endsAt - now in 1..DANGER_MS && now - beatAt >= BEAT_MS
      ) {
        beatAt = now
        scope.audio.play(GameCue.Danger)
        scope.haptics.heartbeat()
      }
      delay(50)
    }
  }
}

private const val DANGER_MS = 10_000L
private const val BEAT_MS = 2_400L
