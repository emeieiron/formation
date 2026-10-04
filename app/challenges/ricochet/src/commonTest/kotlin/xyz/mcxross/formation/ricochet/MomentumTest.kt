package xyz.mcxross.formation.ricochet

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.ricochet.ui.presentedPulse
import xyz.mcxross.formation.session.ChallengeSetup

class MomentumTest {
  private val players = listOf(PlayerId("left"), PlayerId("right"))
  private val paddles = players.mapIndexed { side, player -> Paddle(player, side, 0.85) }

  @Test fun alternatingReturnsAccelerateToTheCapOnEveryDifficulty() {
    for (difficulty in Difficulty.entries) {
      val pacing = Pacing(difficulty)
      var pace = Momentum()
      var speed = pacing.speed
      repeat(12) { index ->
        val side = index % 2
        val direction = if (side == 0) -1 else 1
        val x = if (side == 0) 0.12 else 1.88
        val flight = Physics.step(Pulse(x, 0.85, speed * direction, 0.0), emptyList(), paddles,
          pacing.paddleHeight, 0.10, pace)
        pace = flight.momentum
        speed = hypot(flight.pulse.vx, flight.pulse.vy)
        assertEquals(pacing.speed * pace.factor, speed, 1e-9)
        if (index == 1) assertEquals(pacing.speed * 1.12, speed, 1e-9)
      }
      assertEquals(11, pace.exchanges)
      assertEquals(pacing.speed * 1.9, speed, 1e-9)
      val repeated = Physics.step(Pulse(1.88, 0.85, speed, 0.0), emptyList(), paddles,
        pacing.paddleHeight, 0.10, pace)
      assertEquals(pace, repeated.momentum)
      assertEquals(speed, hypot(repeated.pulse.vx, repeated.pulse.vy), 1e-9)
    }
  }

  @Test fun targetReboundCannotFarmMomentumWithTheSamePaddle() {
    val pace = Momentum(2, 0)
    val speed = 0.62 * pace.factor
    val flight = Physics.step(Pulse(0.15, 0.85, speed, 0.0), listOf(Target(0, 0.36, 0.85)),
      paddles, 0.34, 0.5, pace)
    assertEquals(listOf(ImpactKind.Target, ImpactKind.Paddle), flight.contacts.map { it.kind })
    assertEquals(pace, flight.momentum)
    assertEquals(speed, hypot(flight.pulse.vx, flight.pulse.vy), 1e-9)
  }

  @Test fun predictionAppliesTheSameAccelerationAtContactWithoutMutatingTheFrame() {
    val frame = RicochetGame(ChallengeSetup(players, players.first(), Difficulty.EASY, 7, 0)).state.copy(
      at = 2_000, serveAt = 1_200, targets = emptyList(),
      pulse = Pulse(0.12, 0.85, -0.62, 0.0), momentum = Momentum(lastSide = 1),
    )
    val expected = Physics.step(frame.pulse, frame.targets, frame.paddles, frame.paddleHeight, 0.1, frame.momentum)
    assertEquals(1.12, expected.momentum.factor)
    assertEquals(expected.pulse, presentedPulse(frame, 2_100))
    assertEquals(Momentum(lastSide = 1), frame.momentum)
    val before = Physics.step(frame.pulse, frame.targets, frame.paddles, frame.paddleHeight, 0.05, frame.momentum)
    val after = Physics.step(before.pulse, before.targets, frame.paddles, frame.paddleHeight, 0.05, before.momentum)
    assertEquals(expected.pulse.x, after.pulse.x, 1e-9)
    assertEquals(expected.pulse.vx, after.pulse.vx, 1e-9)
  }

  @Test fun missResetsServeSpeedAndRetainsClearedTargets() {
    val game = RicochetGame(ChallengeSetup(players, players.first(), Difficulty.EASY, 7, 0))
    var now = 0L
    while (game.state.momentum.exchanges < 2 && now < 50_000) {
      game.tick(now)
      ReturnGuide.landing(game.state)?.let { landing ->
        val paddle = game.state.paddles.first { it.side == landing.side }
        game.input(paddle.player, MovePaddle(game.state.rally, paddle.sequence + 1, landing.y), now)
      }
      now += 10
    }
    assertEquals(2, game.state.momentum.exchanges)
    assertTrue(game.state.clears > 0)
    val survivingIds = game.state.targets.map { it.id }.toSet()
    val landing = ReturnGuide.landing(game.state)!!
    val oppositeEnd = if (landing.y > 0.85) 0.17 else 1.53
    for (paddle in game.state.paddles) {
      game.input(paddle.player, MovePaddle(game.state.rally, paddle.sequence + 1, oppositeEnd), now)
    }
    while (game.state.misses == 0 && now < 60_000) game.tick(now.also { now += 10 })
    assertEquals(1, game.state.misses)
    assertEquals(Momentum(), game.state.momentum)
    assertEquals(0.72, hypot(game.state.pulse.vx, game.state.pulse.vy), 1e-9)
    assertTrue(game.state.targets.all { it.id in survivingIds })
  }

  @Test fun closeCallsComeFromTheAuthoritativeContactOffset() {
    val centre = Physics.step(Pulse(0.12, 0.85, -0.62, 0.0), emptyList(), paddles, 0.34, 0.10)
    val edge = Physics.step(Pulse(0.12, 1.0, -0.62, 0.0), emptyList(), paddles, 0.34, 0.10)
    assertEquals(false, centre.contacts.single().grazed)
    assertEquals(true, edge.contacts.single().grazed)
  }
}
