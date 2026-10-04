package xyz.mcxross.formation.ricochet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.mcxross.formation.challenge.GameCue
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.ricochet.ui.FeedbackEvents
import xyz.mcxross.formation.ricochet.ui.dangerPulse
import xyz.mcxross.formation.session.ChallengeSetup

class FeedbackEventsTest {
  private val players = listOf(PlayerId("left"), PlayerId("right"))
  private val state = RicochetGame(ChallengeSetup(players, players.first(), Difficulty.EASY, 7, 0)).state.copy(at = 2_000)
  private fun contact(id: Long, kind: ImpactKind, at: Long = 2_000, side: Int? = 0, grazed: Boolean = false) =
    Impact(id, kind, at, 0.08, 0.85, side = side, grazed = grazed)

  @Test fun restoredRepeatedAndDelayedImpactsStayQuiet() {
    val initial = state.copy(impacts = listOf(contact(1, ImpactKind.Target)))
    val feedback = FeedbackEvents(initial)
    assertNull(feedback.next(initial, 0, 2_000))
    val fresh = initial.copy(impacts = initial.impacts + contact(2, ImpactKind.Paddle))
    assertEquals(GameCue.Return, feedback.next(fresh, 0, 2_020))
    assertNull(feedback.next(fresh, 0, 2_030))
    val stale = fresh.copy(at = 3_000, impacts = fresh.impacts + contact(3, ImpactKind.Target))
    assertNull(feedback.next(stale, 0, 3_000))
    assertNull(feedback.next(stale.copy(at = 3_040), 0, 3_040))
  }

  @Test fun ownCloseCallsAndFastReturnsAreDistinctAndCoalescedFramesChooseOneCue() {
    val feedback = FeedbackEvents(state)
    assertNull(feedback.next(state.copy(impacts = listOf(contact(1, ImpactKind.Paddle, side = 1))), 0, 2_000))
    assertEquals(GameCue.CloseCall, feedback.next(state.copy(impacts = listOf(contact(2, ImpactKind.Paddle, grazed = true))), 0, 2_000))
    assertEquals(GameCue.FastReturn, feedback.next(state.copy(momentum = Momentum(4, 0),
      impacts = listOf(contact(3, ImpactKind.Paddle))), 0, 2_000))
    assertEquals(GameCue.Target, feedback.next(state.copy(impacts = listOf(
      contact(4, ImpactKind.Paddle, grazed = true), contact(5, ImpactKind.Target))), 0, 2_000))
    assertEquals(GameCue.Miss, feedback.next(state.copy(serveAt = 3_200,
      impacts = listOf(contact(6, ImpactKind.Target), contact(7, ImpactKind.Miss))), 0, 2_000))
  }

  @Test fun dangerHasABoundedCadenceAndStopsOnStaleFramesServesAndCompletion() {
    val lastLife = state.copy(misses = 2)
    val feedback = FeedbackEvents(lastLife)
    assertNull(feedback.next(lastLife, 0, 2_000))
    val beat = lastLife.copy(at = 3_600)
    assertEquals(GameCue.Danger, feedback.next(beat, 0, 3_600))
    assertNull(feedback.next(beat, 0, 3_600))
    assertNull(feedback.next(beat, 0, 6_000))
    val resumed = beat.copy(at = 6_040)
    assertNull(feedback.next(resumed, 0, 6_040))
    assertEquals(GameCue.Danger, feedback.next(resumed.copy(at = 8_400), 0, 8_400))
    assertNull(feedback.next(resumed.copy(at = 10_800, serveAt = 12_000), 0, 10_800))
    assertNull(feedback.next(resumed.copy(at = 13_200, finishedAt = 13_200), 0, 13_200))
    assertEquals(1f, dangerPulse(beat, 3_600))
    assertEquals(0.65f, dangerPulse(beat.copy(at = 3_760), 3_760))
    assertEquals(0f, dangerPulse(beat, 6_000))
    assertEquals(0f, dangerPulse(beat.copy(finishedAt = 3_600), 3_600))
    assertEquals(0f, dangerPulse(beat.copy(misses = 1), 3_600))
  }

  @Test fun dangerDoesNotInterruptAnImpactOrPlayAnExpiredResult() {
    val feedback = FeedbackEvents(state)
    feedback.next(state.copy(misses = 2, at = 3_300), 0, 3_300)
    assertEquals(GameCue.Target, feedback.next(state.copy(misses = 2, at = 3_400,
      impacts = listOf(contact(1, ImpactKind.Target, at = 3_400))), 0, 3_400))
    assertNull(feedback.next(state.copy(misses = 2, at = 3_600), 0, 3_600))
    assertNull(feedback.next(state.copy(at = 60_000, impacts = listOf(contact(2, ImpactKind.Target, at = 60_000))), 0, 60_000))
    assertNull(feedback.next(state.copy(at = 61_000, finishedAt = 61_000,
      impacts = listOf(contact(3, ImpactKind.Target, at = 61_000))), 0, 61_000))
  }
}
