package xyz.mcxross.formation.challenge.rush

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.GameStatus

class RushRulesTest {
  private fun players(n: Int) = List(n) { PlayerId("p${it + 1}") }

  private fun game(n: Int = 5, difficulty: Difficulty = Difficulty.NORMAL) =
    players(n).let { RushGame(ChallengeSetup(it, it[0], difficulty, seed = 11, startAt = 0)) }

  @Test
  fun anUnmannedReactorMeltsDown() {
    val game = game()
    var now = 0L
    while (game.status == GameStatus.Running && now < 120_000) {
      now += 33
      game.tick(now)
    }
    val lost = assertIs<GameStatus.Lost>(game.status)
    assertTrue(
      now < RushGame.duration(Difficulty.NORMAL),
      "it melts down before the clock runs out",
    )
    assertNotNull(lost.culprit)
  }

  @Test
  fun anAttentiveCrewKeepsTheCoreAlive() {
    val game = game()
    var now = 0L
    var holding = false
    while (game.status == GameStatus.Running) {
      now += 33
      game.tick(now)
      for (g in game.state.gauges) {
        val who = g.crew.first()
        val mid = (g.lo + g.hi) / 2
        when (g.system) {
          ReactorSystem.COOLANT -> {
            val want = g.value > mid
            if (want != holding) {
              holding = want
              game.input(who, RushInput.Hold(want), now)
            }
          }
          ReactorSystem.CHARGE ->
            if (g.value < mid && now % 132 == 0L) game.input(who, RushInput.Tap, now)
          ReactorSystem.BALANCE ->
            game.input(who, RushInput.Tilt(((mid - g.value) * 8f).coerceIn(-1f, 1f)), now)
          ReactorSystem.PRESSURE -> if (g.value > mid) game.input(who, RushInput.Shake, now)
          ReactorSystem.SPIN -> game.input(who, RushInput.Spin(if (g.value < mid) 3f else 0f), now)
        }
      }
    }
    assertIs<GameStatus.Won>(game.status)
    assertTrue(game.state.level >= 4, "the drift levels up along the way")
  }

  @Test
  fun onlyTheCrewCanWorkASystem() {
    val game = game(n = 3)
    val charge = game.state.gauges.first { it.system == ReactorSystem.CHARGE }
    val outsider = players(3).first { it !in charge.crew }
    val before = charge.value
    game.input(outsider, RushInput.Tap, 10)
    assertEquals(before, game.state.gauges.first { it.system == ReactorSystem.CHARGE }.value)
    game.input(charge.crew.first(), RushInput.Tap, 10)
    assertTrue(game.state.gauges.first { it.system == ReactorSystem.CHARGE }.value > before)
  }

  @Test
  fun smallGroupsGetTouchSystemsAndBigGroupsShare() {
    assertEquals(listOf(ReactorSystem.COOLANT, ReactorSystem.CHARGE), RushGame.systems(2))
    val big = game(n = 7)
    assertEquals(5, big.state.gauges.size)
    assertEquals(listOf(2, 2, 1, 1, 1), big.state.gauges.map { it.crew.size })
  }
}
