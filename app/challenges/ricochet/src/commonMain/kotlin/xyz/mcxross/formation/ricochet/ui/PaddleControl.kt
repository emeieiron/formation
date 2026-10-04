package xyz.mcxross.formation.ricochet.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import xyz.mcxross.formation.ricochet.Arena
import xyz.mcxross.formation.ricochet.MovePaddle
import xyz.mcxross.formation.ricochet.Paddle
import xyz.mcxross.formation.ricochet.RicochetState

@Stable
internal class PaddleControl(paddle: Paddle, rally: Int) {
  private val player = paddle.player
  var y by mutableDoubleStateOf(paddle.y)
    private set
  private var sequence by mutableLongStateOf(paddle.sequence)
  private var rally by mutableIntStateOf(rally)
  private var sentAt = Long.MIN_VALUE

  fun reconcile(paddle: Paddle, currentRally: Int) {
    if (rally != currentRally || paddle.sequence >= sequence) {
      y = paddle.y
      sequence = paddle.sequence
      rally = currentRally
    }
  }

  fun move(position: Double, state: RicochetState, now: Long, force: Boolean, send: (MovePaddle) -> Unit) {
    if (state.finishedAt != null || now < state.startAt || now >= state.endsAt || !position.isFinite()) return
    reconcile(state.paddle(player), state.rally)
    y = Arena.paddleY(position, state.paddleHeight)
    sequence++
    if (!force && sentAt != Long.MIN_VALUE && now - sentAt < 33) return
    sentAt = now
    send(MovePaddle(state.rally, sequence, y))
  }
}
