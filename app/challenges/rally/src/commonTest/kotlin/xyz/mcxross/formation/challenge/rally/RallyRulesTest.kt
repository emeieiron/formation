package xyz.mcxross.formation.challenge.rally

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.GameStatus

class RallyRulesTest {
  private val players = listOf(PlayerId("p1"), PlayerId("p2"), PlayerId("p3"))
  private val start = 10_000L

  private fun game(difficulty: Difficulty = Difficulty.NORMAL) =
    RallyGame(ChallengeSetup(players, players[0], difficulty, seed = 42, startAt = start))

  @Test
  fun perfectReturnsWin() {
    val game = game()
    val receivers = mutableListOf<PlayerId>()
    while (game.status == GameStatus.Running) {
      val ball = assertNotNull(game.state.ball)
      receivers += ball.to
      game.tick(ball.arriveAt)
      game.input(ball.to, RallyInput(ball.id, ball.arriveAt), ball.arriveAt)
    }
    val won = assertIs<GameStatus.Won>(game.status)
    assertEquals(9, game.state.streak)
    assertTrue(won.headline.contains("9"))
    receivers.chunked(3).forEach { lap -> assertEquals(players.toSet(), lap.toSet()) }
    receivers.zipWithNext().forEach { (a, b) -> assertTrue(a != b) }
  }

  @Test
  fun anUnreturnedSparkCostsALifeAndResetsTheStreak() {
    val game = game()
    val first = assertNotNull(game.state.ball)
    game.input(first.to, RallyInput(first.id, first.arriveAt + 20), first.arriveAt + 20)
    assertEquals(1, game.state.streak)

    val second = assertNotNull(game.state.ball)
    game.tick(second.arriveAt + second.window + 500)
    val miss = assertIs<RallyEvent.Miss>(game.state.event)
    assertEquals(second.to, miss.player)
    assertEquals(null, miss.early)
    assertEquals(0, game.state.streak)
    assertEquals(2, game.state.lives)
    assertEquals(GameStatus.Running, game.status)
    assertNotNull(game.state.ball, "a new serve follows a miss")
  }

  @Test
  fun swingingEarlyIsAMiss() {
    val game = game()
    val ball = assertNotNull(game.state.ball)
    val at = ball.arriveAt - ball.window - 200
    game.input(ball.to, RallyInput(ball.id, at), at)
    assertEquals(true, assertIs<RallyEvent.Miss>(game.state.event).early)
  }

  @Test
  fun onlyTheReceiverCanReturnAndFarEarlyTapsDoNotCount() {
    val game = game()
    val ball = assertNotNull(game.state.ball)
    val other = players.first { it != ball.to }
    game.input(other, RallyInput(ball.id, ball.arriveAt), ball.arriveAt)
    assertEquals(0, game.state.streak)
    game.input(ball.to, RallyInput(ball.id, ball.launchAt), ball.launchAt)
    assertEquals(null, game.state.event)
    game.input(ball.to, RallyInput(ball.id, ball.arriveAt), ball.arriveAt)
    assertEquals(1, game.state.streak)
  }

  @Test
  fun losingEveryLifeLosesTheRally() {
    val game = game(Difficulty.EXTREME)
    var now = start
    while (game.status == GameStatus.Running) {
      val ball = assertNotNull(game.state.ball)
      now = ball.arriveAt + 2_000
      game.tick(now)
    }
    val lost = assertIs<GameStatus.Lost>(game.status)
    assertEquals("missed the return", lost.reason)
    assertNotNull(lost.culprit)
    assertEquals(0, game.state.lives)
  }

  @Test
  fun theWindowTightensAsTheStreakGrows() {
    val game = game()
    val first = assertNotNull(game.state.ball)
    repeat(6) {
      val ball = assertNotNull(game.state.ball)
      game.input(ball.to, RallyInput(ball.id, ball.arriveAt), ball.arriveAt)
    }
    val later = assertNotNull(game.state.ball)
    assertTrue(later.window < first.window)
    assertTrue(later.arriveAt - later.launchAt < first.arriveAt - first.launchAt)
  }
}
