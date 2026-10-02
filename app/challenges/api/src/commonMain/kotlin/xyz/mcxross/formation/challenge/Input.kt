package xyz.mcxross.formation.challenge

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import xyz.mcxross.formation.sensors.Gesture
import xyz.mcxross.formation.sensors.MotionSense
import xyz.mcxross.formation.sensors.Pose
import xyz.mcxross.formation.session.ClockSync

@Composable
fun OnGesture(motion: MotionSense, enabled: Boolean = true, onGesture: (Gesture) -> Unit) {
  val handler by rememberUpdatedState(onGesture)
  if (enabled) LaunchedEffect(motion) { motion.gestures.collect { handler(it) } }
}

@Composable
fun rememberPose(motion: MotionSense): State<Pose?> {
  val pose = remember { androidx.compose.runtime.mutableStateOf(motion.pose.value) }
  LaunchedEffect(motion) { motion.pose.collect { pose.value = it } }
  return pose
}

// Stamps the Seeker's time when the finger lands, not when it lifts.
fun Modifier.onTouchDown(
  clock: ClockSync,
  enabled: Boolean = true,
  onDown: (hostTime: Long, at: Offset) -> Unit,
): Modifier =
  if (!enabled) this
  else
    pointerInput(clock) {
      detectTapGestures(onPress = { offset -> onDown(clock.hostNow(), offset) })
    }

