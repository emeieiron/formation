package xyz.mcxross.formation.ricochet.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import xyz.mcxross.formation.challenge.GameCue
import xyz.mcxross.formation.challenge.StageScope
import xyz.mcxross.formation.ricochet.MovePaddle
import xyz.mcxross.formation.ricochet.RicochetState

@Composable
internal fun RicochetFeedback(scope: StageScope<RicochetState, MovePaddle>, side: Int) {
  val events = remember(scope.round, scope.me) { FeedbackEvents(scope.state) }
  LaunchedEffect(events) {
    while (isActive) {
      events.next(scope.state, side, scope.clock.hostNow())?.let { cue ->
        scope.audio.play(cue)
        when (cue) {
          GameCue.Miss -> scope.haptics.reject()
          GameCue.Target -> scope.haptics.success()
          GameCue.Danger -> scope.haptics.heartbeat()
          else -> scope.haptics.heavy()
        }
      }
      delay(50)
    }
  }
}
