package xyz.mcxross.formation.challenge.formation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.sensors.Pose
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.GameStatus

class FormationRulesTest {
  private val seeker = PlayerId("p1")
  private val players = listOf(seeker, PlayerId("p2"), PlayerId("p3"), PlayerId("p4"))

  private fun game() =
    FormationGame(ChallengeSetup(players, seeker, Difficulty.NORMAL, seed = 5, startAt = 0))

  private fun FormationGame.strike(at: Long) {
    val figure = assertNotNull(state.figure)
    players.forEach { input(it, FormationInput(figure.targets.getValue(it.value)), at) }
  }

  @Test
  fun holdingEveryFigureWins() {
    val game = game()
    var now = 1_000L
    repeat(4) {
      val figure = assertNotNull(game.state.figure)
      assertEquals(
        Pose.FACE_UP,
        figure.targets[seeker.value],
        "the Seeker lies face up and shows the blueprint",
      )
      assertTrue(
        figure.targets.filterKeys { it != seeker.value }.values.toSet().size >= 2,
        "a figure mixes poses",
      )
      game.strike(now)
      assertNotNull(game.state.holdFrom)
      now += game.state.holdMs + 10
      game.tick(now)
      assertIs<FigureEvent.Locked>(game.state.event)
      now += FormationGame.PAUSE_MS
      game.tick(now)
    }
    assertIs<GameStatus.Won>(game.status)
  }

  @Test
  fun movingBeforeTheLockResetsTheHold() {
    val game = game()
    game.strike(1_000)
    val figure = assertNotNull(game.state.figure)
    val wobbler = players[2]
    val wrong = Pose.entries.first { it != figure.targets[wobbler.value] && it != Pose.TILTED }
    game.input(wobbler, FormationInput(wrong), 1_500)
    assertNull(game.state.holdFrom)
    game.tick(1_000L + game.state.holdMs + 50)
    assertEquals(0, game.state.done)
  }

  @Test
  fun runningOutOfTimeNamesWhoWasOutOfPlace() {
    val game = game()
    val figure = assertNotNull(game.state.figure)
    players.drop(1).forEach {
      game.input(it, FormationInput(figure.targets.getValue(it.value)), 1_000)
    }
    game.tick(figure.deadline + 1)
    val timedOut = assertIs<FigureEvent.TimedOut>(game.state.event)
    assertEquals(listOf(seeker), timedOut.missing)
    assertEquals(2, game.state.lives)
  }

  @Test
  fun nobodyIsAskedForThePoseTheyAlreadyHold() {
    val game = game()
    var now = 1_000L
    repeat(3) {
      game.strike(now)
      now += game.state.holdMs + 10
      game.tick(now)
      now += FormationGame.PAUSE_MS
      game.tick(now)
      val next = assertNotNull(game.state.figure)
      players.drop(1).forEach { p ->
        assertTrue(next.targets[p.value] != game.state.poses[p.value], "$p must move")
      }
    }
  }
}
