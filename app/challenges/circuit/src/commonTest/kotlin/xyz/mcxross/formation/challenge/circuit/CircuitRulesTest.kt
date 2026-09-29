package xyz.mcxross.formation.challenge.circuit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.GameStatus

class CircuitRulesTest {
  private val players = listOf(PlayerId("p1"), PlayerId("p2"), PlayerId("p3"), PlayerId("p4"))
  private val start = 5_000L

  private fun game(seed: Long = 3, difficulty: Difficulty = Difficulty.NORMAL) =
    CircuitGame(ChallengeSetup(players, players[0], difficulty, seed, start))

  private fun CircuitGame.playPerfectly(p: Pulse) {
    val at = p.arriveAt + 100
    tick(at)
    val input = CircuitInput(p.id, p.action, at, choice = p.symbol)
    input(players[p.node], input, at)
    p.partner?.let { input(players[it], input.copy(at = at + 40), at + 40) }
  }

  @Test
  fun aFlawlessCircuitCompletesEveryLoop() {
    val game = game()
    val seen = mutableSetOf<Action>()
    var moves = 0
    while (game.status == GameStatus.Running) {
      val p = assertNotNull(game.state.pulse)
      seen += p.action
      game.playPerfectly(p)
      moves++
    }
    assertIs<GameStatus.Won>(game.status)
    assertEquals(game.state.total, game.state.passed)
    assertEquals(3, game.state.loops)
    assertTrue(moves <= game.state.total)
    assertTrue(seen.size >= 3, "a circuit mixes up its moves: $seen")
  }

  @Test
  fun runningOutOfTimeBreaksTheCircuitAndItRestartsThere() {
    val game = game()
    val p = assertNotNull(game.state.pulse)
    game.tick(p.deadline + 1_000)
    val broke = assertIs<CircuitEvent.Broke>(game.state.event)
    assertEquals(p.node, broke.node)
    assertEquals("ran out of time", broke.reason)
    assertEquals(2, game.state.lives)
    assertNull(game.state.pulse)

    game.tick(p.deadline + 1_000 + CircuitGame.RECOVER_MS)
    assertEquals(p.node, assertNotNull(game.state.pulse).node)
  }

  @Test
  fun aWrongSymbolBreaksAMatch() {
    var seed = 0L
    while (true) {
      val game = game(seed++)
      repeat(20) {
        val p = game.state.pulse ?: return@repeat
        if (p.action == Action.MATCH) {
          val wrong = p.options.first { it != p.symbol }
          game.input(
            players[p.node],
            CircuitInput(p.id, Action.MATCH, p.arriveAt, wrong),
            p.arriveAt,
          )
          assertEquals(
            "picked the wrong symbol",
            assertIs<CircuitEvent.Broke>(game.state.event).reason,
          )
          assertEquals((p.node - 1 + players.size) % players.size, p.caller)
          return
        }
        game.playPerfectly(p)
      }
    }
  }

  @Test
  fun syncPartnersMustTapTogether() {
    var seed = 0L
    while (true) {
      val game = game(seed++)
      repeat(20) {
        val p = game.state.pulse ?: return@repeat
        if (p.action == Action.SYNC) {
          val partner = assertNotNull(p.partner)
          assertEquals(p.node + 1, partner)
          game.input(players[p.node], CircuitInput(p.id, Action.SYNC, p.arriveAt), p.arriveAt)
          assertEquals(listOf(p.node), assertNotNull(game.state.pulse).synced)
          val late = p.arriveAt + CircuitGame.SYNC_WINDOW_MS + 200
          game.input(players[partner], CircuitInput(p.id, Action.SYNC, late), late)
          val broke = assertIs<CircuitEvent.Broke>(game.state.event)
          assertEquals(partner, broke.node)
          assertEquals("tapped out of sync", broke.reason)
          return
        }
        game.playPerfectly(p)
      }
    }
  }

  @Test
  fun onlyThePulsedNodeCanAct() {
    val game = game()
    val p = assertNotNull(game.state.pulse)
    val bystander = players.indices.first { it != p.node && it != p.partner }
    game.input(players[bystander], CircuitInput(p.id, p.action, p.arriveAt, p.symbol), p.arriveAt)
    assertEquals(0, game.state.passed)
    assertEquals(p, game.state.pulse)
  }

  @Test
  fun loopsScaleWithTheGroup() {
    assertEquals(4, CircuitGame.loops(2, Difficulty.NORMAL))
    assertEquals(3, CircuitGame.loops(4, Difficulty.NORMAL))
    assertEquals(2, CircuitGame.loops(10, Difficulty.NORMAL))
    assertEquals(1, CircuitGame.loops(20, Difficulty.NORMAL))
  }
}
