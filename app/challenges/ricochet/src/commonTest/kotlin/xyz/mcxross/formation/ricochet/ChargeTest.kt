package xyz.mcxross.formation.ricochet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.ricochet.ui.presentedFlight
import xyz.mcxross.formation.session.ChallengeSetup

class ChargeTest {
  private val players = listOf(PlayerId("left"), PlayerId("right"))
  private val paddles = players.mapIndexed { side, player -> Paddle(player, side, 0.85) }

  @Test fun twoAlternatingExchangesChargeOnceAndSamePlayerReturnsCannotCharge() {
    var charge = Charge()
    var momentum = Momentum()
    for (side in listOf(0, 0, 1, 0, 1)) {
      val speed = 0.72 * momentum.factor
      val flight = Physics.step(Pulse(if (side == 0) 0.12 else 1.88, 0.85,
        if (side == 0) -speed else speed, 0.0), emptyList(), paddles, 0.34, 0.10, momentum, charge)
      if (momentum.exchanges < 1) assertFalse(flight.charge.armed)
      if (side == 0 && momentum.exchanges == 1) {
        assertTrue(flight.charge.armed)
        assertEquals(1, flight.contacts.count { it.kind == ImpactKind.Charge })
      }
      if (charge.armed) assertEquals(0, flight.contacts.count { it.kind == ImpactKind.Charge })
      momentum = flight.momentum
      charge = flight.charge
    }
    assertTrue(charge.armed)
    assertEquals(2, charge.progress)
  }

  @Test fun onlyTheFirstChargedTargetPiercesThenNormalReflectionResumes() {
    val targets = listOf(Target(0, 0.5, 0.85), Target(1, 0.9, 0.85))
    val flight = Physics.step(Pulse(0.2, 0.85, 1.0, 0.0), targets, paddles, 0.34, 0.65,
      charge = Charge(2, true))
    assertEquals(listOf(ImpactKind.Pierce, ImpactKind.Target), flight.contacts.map { it.kind })
    assertTrue(flight.targets.isEmpty())
    assertTrue(flight.pulse.vx < 0)
    assertEquals(Charge(), flight.charge)
    val escaped = Physics.step(Pulse(0.12, 0.3, -1.0, 0.0), targets, paddles, 0.34, 0.2,
      Momentum(4, 1), Charge(2, true))
    assertTrue(escaped.missed)
    assertEquals(Momentum(), escaped.momentum)
    assertEquals(Charge(), escaped.charge)
  }

  @Test fun predictionAndGuidanceFollowTheChargedTrajectory() {
    val frame = RicochetGame(ChallengeSetup(players, players.first(), Difficulty.EASY, 7, 0)).state.copy(
      at = 2_000, pulse = Pulse(0.32, 0.85, 1.0, 0.0), targets = listOf(Target(0, 0.5, 0.85)),
      charge = Charge(2, true),
    )
    val projected = presentedFlight(frame, 2_100)
    assertEquals(ImpactKind.Pierce, projected.contacts.single().kind)
    assertTrue(projected.pulse.vx > 0)
    assertFalse(projected.charge.armed)
    assertTrue(frame.charge.armed)
    assertEquals(1, ReturnGuide.landing(frame)!!.side)
  }

  @Test fun difficultyUsesTheDeclaredDeadlineAndServeSpeed() {
    val speeds = listOf(0.72, 0.96, 1.18, 1.40)
    val deadlines = listOf(60_000L, 50_000L, 45_000L, 40_000L)
    Difficulty.entries.forEachIndexed { index, difficulty ->
      val game = RicochetGame(ChallengeSetup(players, players.first(), difficulty, 7, 1_000))
      assertEquals(1_000 + deadlines[index], game.state.endsAt)
      assertEquals(speeds[index], kotlin.math.hypot(game.state.pulse.vx, game.state.pulse.vy), 1e-9)
    }
  }
}
