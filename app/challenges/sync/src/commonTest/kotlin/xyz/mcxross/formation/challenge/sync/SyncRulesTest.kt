package xyz.mcxross.formation.challenge.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.GameStatus

class SyncRulesTest {
  private val players = listOf(PlayerId("p1"), PlayerId("p2"), PlayerId("p3"))

  private fun game(difficulty: Difficulty = Difficulty.NORMAL) =
    SyncGame(ChallengeSetup(players, players[0], difficulty, seed = 9, startAt = 1_000))

  private fun SyncGame.play(offsets: Map<PlayerId, Long?> = emptyMap()): Long {
    val round = assertNotNull(state.round)
    for (p in players) {
      val offset = if (p in offsets) offsets[p] else 0L
      if (offset != null)
        input(p, SyncInput(round.attempt, round.moment + offset), round.moment + offset)
    }
    val judged = round.moment + 2_000
    tick(judged)
    return judged
  }

  @Test
  fun landingEveryRoundTogetherWins() {
    val game = game()
    var now = 0L
    repeat(4) { i ->
      if (i > 0) game.tick(now + SyncGame.RESULT_MS)
      val round = assertNotNull(game.state.round)
      assertEquals(players.size, round.tasks.values.toSet().size, "everyone gets a different move")
      if (i == 0) assertTrue(round.tasks.values.none { it.motion }, "the first round is touch only")
      now = game.play()
      assertTrue(assertNotNull(game.state.result).passed)
    }
    assertIs<GameStatus.Won>(game.status)
    assertEquals(4, game.state.done)
  }

  @Test
  fun theSecondHalfGoesBlind() {
    val game = game()
    var now = 0L
    val blind = mutableListOf<Boolean>()
    repeat(4) { i ->
      if (i > 0) game.tick(now + SyncGame.RESULT_MS)
      blind += assertNotNull(game.state.round).blindFrom != null
      now = game.play()
    }
    assertEquals(listOf(false, false, true, true), blind)
  }

  @Test
  fun oneLatePlayerFailsTheRound() {
    val game = game()
    val round = assertNotNull(game.state.round)
    game.play(mapOf(players[2] to round.window + 200L))
    val result = assertNotNull(game.state.result)
    assertFalse(result.passed)
    assertEquals(players[2], result.culprit)
    assertEquals(2, game.state.lives)
    assertEquals(0, game.state.done)
    assertNull(game.state.round, "the result shows before the retry")
  }

  @Test
  fun motionMovesGetMoreSlack() {
    val game = game()
    game.play()
    game.tick(100_000)
    val round = assertNotNull(game.state.round)
    val (moving, task) = round.tasks.entries.first { it.value.motion }
    val late = round.window + task.extraMs - 20L
    game.play(mapOf(PlayerId(moving) to late))
    assertTrue(assertNotNull(game.state.result).passed)
  }

  @Test
  fun someoneWhoNeverMovesLosesItInTheEnd() {
    val game = game(Difficulty.EXTREME)
    var now = game.play(mapOf(players[1] to null))
    game.tick(now + SyncGame.RESULT_MS)
    now = game.play(mapOf(players[1] to null))
    val lost = assertIs<GameStatus.Lost>(game.status)
    assertEquals(players[1], lost.culprit)
    assertEquals("never made their move", lost.reason)
  }

  @Test
  fun lateJoinersOfARoundAreIgnored() {
    val game = game()
    val round = assertNotNull(game.state.round)
    game.input(PlayerId("stranger"), SyncInput(round.attempt, round.moment), round.moment)
    game.input(players[0], SyncInput(round.attempt + 5, round.moment), round.moment)
    assertTrue(game.state.acted.isEmpty())
  }
}
