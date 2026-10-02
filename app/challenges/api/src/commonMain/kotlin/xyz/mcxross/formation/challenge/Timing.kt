package xyz.mcxross.formation.challenge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import xyz.mcxross.formation.session.ClockSync

// Refreshed every frame: read it in draw code to animate without recomposing.
@Composable
fun rememberHostNow(clock: ClockSync): State<Long> {
  val now = remember { mutableLongStateOf(clock.hostNow()) }
  LaunchedEffect(clock) { while (true) withFrameMillis { now.longValue = clock.hostNow() } }
  return now
}
