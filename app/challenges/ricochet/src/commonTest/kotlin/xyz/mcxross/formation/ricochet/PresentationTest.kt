package xyz.mcxross.formation.ricochet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.ricochet.ui.PaddleControl
import xyz.mcxross.formation.ricochet.ui.presentedPulse
import xyz.mcxross.formation.session.ChallengeSetup

class PresentationTest {
  private val me = PlayerId("left")
  private val state =
    RicochetGame(ChallengeSetup(listOf(me, PlayerId("right")), me, Difficulty.EASY, 7, 0)).state

  @Test
  fun predictionStopsAtTheCapAndNeverMutatesAuthoritativeState() {
    val frame = state.copy(at = 2_000, serveAt = 1_200, pulse = Pulse(0.90, 0.45, 0.62, 0.0))
    val projected = presentedPulse(frame, 2_100)
    assertTrue(projected.x > 0.90)
    assertEquals(projected, presentedPulse(frame, 8_000))
    assertEquals(0.90, frame.pulse.x)
    assertEquals(frame.pulse, presentedPulse(frame.copy(finishedAt = 2_000), 2_100))
  }

  @Test
  fun unacknowledgedTouchSurvivesOldFramesAndReleaseSendsTheLatestPosition() {
    val control = PaddleControl(state.paddle(me), state.rally)
    val sent = mutableListOf<MovePaddle>()
    control.move(0.4, state, 100, true, sent::add)
    control.move(0.6, state, 110, false, sent::add)
    assertEquals(1, sent.size)
    control.reconcile(state.paddle(me).copy(sequence = sent.first().sequence, y = 0.4), state.rally)
    assertEquals(0.6, control.y)
    control.move(0.6, state, 115, true, sent::add)
    assertEquals(0.6, sent.last().y)
    assertTrue(sent.last().sequence > sent.first().sequence)
    control.reconcile(state.paddle(me), state.rally + 1)
    assertEquals(state.paddle(me).y, control.y)
  }
}
