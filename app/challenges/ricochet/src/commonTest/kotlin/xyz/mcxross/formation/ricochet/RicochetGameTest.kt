package xyz.mcxross.formation.ricochet

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.GameStatus

class RicochetGameTest {
  private val left = PlayerId("left")
  private val right = PlayerId("right")
  private val paddles = listOf(Paddle(left, 0, 0.85), Paddle(right, 1, 0.85))

  private fun game(seed: Long = 41) =
    RicochetGame(ChallengeSetup(listOf(left, right), left, Difficulty.EASY, seed, 1_000))

  @Test
  fun sweepsTargetsInsteadOfTunnellingOnLongSteps() {
    val target = Target(0, 0.70, 0.85)
    val flight = Physics.step(Pulse(0.25, 0.85, 3.0, 0.0), listOf(target), paddles, 0.34, 0.20)
    assertTrue(flight.targets.isEmpty())
    assertEquals(1, flight.contacts.count { it.kind == ImpactKind.Target })
    assertTrue(flight.pulse.vx < 0)
    assertFalse(flight.missed)
  }

  @Test
  fun returnsKeepTheirSpeedAndAimFromTheContactOffset() {
    val flight = Physics.step(Pulse(0.12, 0.94, -0.62, 0.0), emptyList(), paddles, 0.34, 0.10)
    assertEquals(ImpactKind.Paddle, flight.contacts.single().kind)
    assertTrue(flight.pulse.vx > 0 && flight.pulse.vy > 0)
    assertEquals(0.62, hypot(flight.pulse.vx, flight.pulse.vy), 1e-9)
  }

  @Test
  fun crossesTheSeamWithoutASeparateTransferOrHeightChange() {
    val flight = Physics.step(Pulse(0.97, 0.50, 0.62, 0.0), emptyList(), paddles, 0.34, 0.10)
    assertTrue(flight.pulse.x > 1.0)
    assertEquals(0.50, flight.pulse.y)
    assertTrue(flight.contacts.isEmpty())
  }

  @Test
  fun wallsReflectAndAnUncaughtPulseProducesOneMiss() {
    val wall = Physics.step(Pulse(0.4, 0.05, 0.3, -0.6), emptyList(), paddles, 0.34, 0.10)
    assertTrue(wall.pulse.vy > 0)
    assertEquals(ImpactKind.Wall, wall.contacts.single().kind)
    val escape = Physics.step(Pulse(0.12, 0.3, -0.62, 0.0), emptyList(), paddles, 0.34, 0.30)
    assertTrue(escape.missed)
    assertEquals(ImpactKind.Miss, escape.contacts.single().kind)
  }

  @Test
  fun admitsOnlyNewFiniteCommandsFromTheCurrentPlayersAndRally() {
    val game = game()
    val opening = game.state
    game.input(PlayerId("stranger"), MovePaddle(1, 1, 0.3), 1_000)
    game.input(left, MovePaddle(1, 1, Double.NaN), 1_000)
    game.input(left, MovePaddle(1, 1, Double.POSITIVE_INFINITY), 1_000)
    game.input(left, MovePaddle(0, 1, 0.3), 1_000)
    game.input(left, MovePaddle(1, 0, 0.3), 1_000)
    assertEquals(opening, game.state)
    game.input(left, MovePaddle(1, 2, -4.0), 1_000)
    assertEquals(opening.paddleHeight / 2, game.state.paddle(left).y)
    game.input(left, MovePaddle(1, 1, 0.8), 1_000)
    game.input(left, MovePaddle(1, 2, 0.9), 1_000)
    assertEquals(2, game.state.paddle(left).sequence)
    assertEquals(opening.paddleHeight / 2, game.state.paddle(left).y)
  }

  @Test
  fun fixedStepsReplayIdenticallyAcrossDifferentTickCadences() {
    val frequent = game()
    val delayed = game()
    for (now in 1_000L..9_000L step 25) frequent.tick(now)
    delayed.tick(9_000)
    assertEquals(frequent.state, delayed.state)
    assertEquals(frequent.status, delayed.status)
  }

  @Test
  fun missesResetOnlyTheServeAndOldRallyCommandsCannotMoveThePaddle() {
    val game = game()
    game.input(left, MovePaddle(1, 1, 0.17), 1_000)
    game.input(right, MovePaddle(1, 1, 0.17), 1_000)
    var now = 1_000L
    while (game.state.misses == 0 && now < 61_000) game.tick(now.also { now += 10 })
    assertEquals(1, game.state.misses)
    assertEquals(2, game.state.rally)
    assertTrue(game.state.serveAt > game.state.at)
    val targets = game.state.targets
    val paddle = game.state.paddle(left)
    game.input(left, MovePaddle(1, 99, 1.4), game.state.at)
    assertEquals(paddle, game.state.paddle(left))
    game.tick(game.state.serveAt)
    assertEquals(targets, game.state.targets)
  }

  @Test
  fun terminalResultsStayFrozenAndLargeTicksCannotSpendMoreThanThreeLives() {
    val game = game()
    game.input(left, MovePaddle(1, 1, 0.17), 1_000)
    game.input(right, MovePaddle(1, 1, 0.17), 1_000)
    game.tick(121_000)
    assertIs<GameStatus.Lost>(game.status)
    assertEquals(3, game.state.misses)
    assertTrue(game.state.finishedAt!! <= game.state.endsAt)
    val final = game.state
    game.tick(181_000)
    game.input(left, MovePaddle(game.state.rally, 100, 0.9), 181_000)
    assertEquals(final, game.state)
  }

  @Test
  fun requiresTwoDistinctPlayers() {
    assertFailsWith<IllegalArgumentException> {
      RicochetGame(ChallengeSetup(listOf(left, left), left, Difficulty.EASY, 1, 0))
    }
  }
}
