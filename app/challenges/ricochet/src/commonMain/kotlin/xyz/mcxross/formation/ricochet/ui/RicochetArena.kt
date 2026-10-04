package xyz.mcxross.formation.ricochet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.ricochet.Arena
import xyz.mcxross.formation.ricochet.RicochetState

@Composable
internal fun RicochetArena(state: RicochetState, side: Int, now: State<Long>, onMove: (Double, Boolean) -> Unit, modifier: Modifier) {
  val colors = Theme.colors
  val move by rememberUpdatedState(onMove)
  Canvas(modifier.semantics {
    contentDescription = "${if (side == 0) "Left" else "Right"} paddle. Drag vertically to aim the return."
    progressBarRangeInfo = ProgressBarRangeInfo(state.paddles.first { it.side == side }.y.toFloat(),
      (state.paddleHeight / 2).toFloat()..(Arena.HEIGHT - state.paddleHeight / 2).toFloat())
    setProgress { move(it.toDouble(), true); true }
  }.pointerInput(side) {
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false)
      fun position(y: Float) = viewport(size.width.toFloat(), size.height.toFloat()).arenaY(y)
      move(position(down.position.y), true)
      down.consume()
      do {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
        if (change.position != change.previousPosition || !change.pressed) move(position(change.position.y), !change.pressed)
        change.consume()
      } while (change.pressed)
    }
  }) {
    val view = viewport(size.width, size.height)
    translate(view.x, view.y) {
      scale(view.scale, view.scale, Offset.Zero) {
        clipRect(0f, 0f, 1f, Arena.HEIGHT.toFloat()) { drawHalf(state, side, now.value, colors) }
      }
    }
  }
}
