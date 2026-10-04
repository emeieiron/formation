package xyz.mcxross.formation.ricochet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import xyz.mcxross.formation.challenge.playOut
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.GameStatus

class CompletionTest {
  private val players = listOf(PlayerId("left"), PlayerId("right"))

  @Test fun completesSeededRoundsThroughOrdinaryCommandsOnEveryDifficulty() {
    for (difficulty in Difficulty.entries) for (seed in 0L..7L) {
      assertIs<GameStatus.Won>(Ricochet.playOut(ChallengeSetup(players, players.first(), difficulty, seed, 0)),
        "$difficulty seed $seed")
    }
  }

  @Test fun deadlineEndsAnUnfinishedRallyAndRejectsFurtherPlay() {
    val game = RicochetGame(ChallengeSetup(players, players.first(), Difficulty.EASY, 7, 0))
    for (now in 0L..59_000L step 40) {
      game.tick(now)
      ReturnGuide.landing(game.state)?.let { landing ->
        val paddle = game.state.paddles.first { it.side == landing.side }
        game.input(paddle.player, MovePaddle(game.state.rally, paddle.sequence + 1, landing.y), now)
      }
    }
    assertEquals(GameStatus.Running, game.status)
    assertTrue(game.state.targets.isNotEmpty())
    game.tick(60_000)
    assertEquals("The formation ran out of time.", assertIs<GameStatus.Lost>(game.status).reason)
    assertEquals(60_000, game.state.finishedAt)
    val final = game.state
    game.input(players.first(), MovePaddle(game.state.rally, 99999, 0.8), 60_040)
    assertEquals(final, game.state)
  }
}
