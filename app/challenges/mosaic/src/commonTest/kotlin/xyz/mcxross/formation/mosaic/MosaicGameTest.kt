package xyz.mcxross.formation.mosaic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.GameStatus

class MosaicGameTest {
  private val game = MosaicGame(setup())
  private val state get() = game.state
  private fun at(position: Int) = state.positions[position]
  private val go = START + 1_000

  // 3 x 2 landscape: position 0 is top left, 1 top right, 2 middle left and so on.
  private fun seal(first: Int, edge: Edge, second: Int, now: Long, along: Double = 30.0, offset: Double = 0.0) {
    game.input(at(first), pinch(now, edge, along, now), now)
    game.input(at(second), pinch(now, edge.opposite, along + offset, now), now)
  }

  @Test
  fun aPinchAcrossNeighboursSealsTheirSeam() {
    seal(0, Edge.Right, 1, go)
    assertEquals(listOf(0), state.sealed)
    assertEquals(SeamOutcome.Sealed, state.events.last().outcome)
    assertEquals(setOf(at(0), at(1)), state.events.last().players.toSet())
    seal(0, Edge.Bottom, 2, go + 500)
    assertEquals(2, state.sealed.size)
    assertEquals(0, state.misses)
  }

  @Test
  fun misalignedNeighboursAreToldToLineUpWithoutAMiss() {
    seal(2, Edge.Right, 3, go, offset = 12.0)
    assertEquals(emptyList(), state.sealed)
    assertEquals(SeamOutcome.Misaligned, state.events.last().outcome)
    assertEquals(0, state.misses)
    seal(2, Edge.Right, 3, go + 600, offset = 5.0)
    assertEquals(1, state.sealed.size)
  }

  @Test
  fun aWrongPairCostsASharedMissOnceItHasSettled() {
    // Top left and middle right face each other's edges but aren't neighbours.
    game.input(at(0), pinch(go, Edge.Right, 30.0, go), go)
    game.input(at(3), pinch(go, Edge.Left, 30.0, go + 20), go + 20)
    assertEquals(0, state.misses, "A real neighbour could still arrive")
    game.tick(go + MosaicGame.SETTLE_MS)
    assertEquals(1, state.misses)
    assertEquals(SeamOutcome.Wrong, state.events.last().outcome)
  }

  @Test
  fun twoPinchesAtOnceEachFindTheirOwnNeighbour() {
    // Both rows pinch together, so each half also faces a half from the other row.
    game.input(at(0), pinch(go, Edge.Right, 30.0, go), go)
    game.input(at(2), pinch(go + 1, Edge.Right, 30.0, go + 10), go + 10)
    game.input(at(3), pinch(go + 1, Edge.Left, 30.0, go + 15), go + 15)
    game.input(at(1), pinch(go + 1, Edge.Left, 30.0, go + 30), go + 30)
    game.tick(go + 1_000)
    assertEquals(setOf(0, 1), state.sealed.toSet())
    assertEquals(0, state.misses)
  }

  @Test
  fun loneStaleDuplicateAndOuterHalvesChangeNothing() {
    game.input(at(0), pinch(go, Edge.Right, 30.0, go), go)
    game.tick(go + 2_000)
    game.input(at(1), pinch(go, Edge.Left, 30.0, go), go + 2_000)
    game.input(at(0), pinch(go, Edge.Right, 30.0, go + 2_000), go + 2_000)
    game.input(at(4), pinch(go + 2_000, Edge.Left, 30.0, go + 2_000), go + 2_000)
    game.input(PlayerId("stranger"), pinch(go + 2_000, Edge.Left, 30.0, go + 2_000), go + 2_000)
    game.tick(go + 4_000)
    assertEquals(emptyList(), state.sealed)
    assertEquals(0, state.misses)
    assertEquals(emptyList(), state.events)
  }

  @Test
  fun aReleaseStampCannotReachBackPastTheLateWindow() {
    game.input(at(0), pinch(go, Edge.Right, 30.0, go), go)
    game.input(at(1), pinch(go + 1, Edge.Left, 30.0, go), go + MosaicGame.LATE_MS + MosaicGame.PAIR_MS + 50)
    assertEquals(emptyList(), state.sealed)
  }

  @Test
  fun theThirdWrongPairEndsTheAttempt() {
    repeat(3) { attempt ->
      val now = go + attempt * 1_000L
      game.input(at(0), pinch(now, Edge.Right, 30.0, now), now)
      game.input(at(3), pinch(now, Edge.Left, 30.0, now), now)
      game.tick(now + MosaicGame.SETTLE_MS)
    }
    val lost = assertIs<GameStatus.Lost>(game.status)
    assertTrue("wrong pairs" in lost.reason)
    assertEquals(MosaicState.MAX_MISSES, state.misses)
    assertTrue(state.finishedAt != null)
  }

  @Test
  fun theClockEndsTheAttempt() {
    assertEquals(START + 20_000 + 7 * 7_000, state.endsAt)
    game.tick(state.endsAt - 1)
    assertEquals(GameStatus.Running, game.status)
    game.tick(state.endsAt)
    assertIs<GameStatus.Lost>(game.status)
    assertEquals(state.endsAt, state.finishedAt)
  }

  @Test
  fun sealingEverySeamWinsOnce() {
    var now = go
    state.seams.forEach { seam ->
      val edge = seam.edgeOf(seam.first)!!
      seal(seam.first, edge, seam.second, now)
      now += 500
    }
    assertIs<GameStatus.Won>(game.status)
    assertEquals(now - 500, state.finishedAt)
    val sealed = state.sealed
    seal(0, Edge.Right, 1, now)
    assertEquals(sealed, state.sealed)
  }

  @Test
  fun debugAssistanceCompletesEverySizeAndDifficulty() {
    for (players in Layouts.groupSizes) for (difficulty in Difficulty.entries) for (seed in 1L..3L) {
      val play = MosaicGame(setup(players, difficulty, seed))
      val sent = mutableSetOf<String>()
      var now = START
      while (play.status == GameStatus.Running) {
        now += 40
        play.tick(now)
        for (player in play.state.positions) {
          val (key, pinch) = Assist.pinch(play.state, player, now) ?: continue
          if (sent.add("$player:$key")) play.input(player, pinch, now)
        }
      }
      assertIs<GameStatus.Won>(play.status, "$players phones on $difficulty, seed $seed")
      assertEquals(0, play.state.misses)
    }
  }

  @Test
  fun easyLabelsFragmentsAndHardHidesUnsealedSeams() {
    assertTrue(MosaicGame(setup(difficulty = Difficulty.EASY)).state.labels)
    assertTrue(!state.labels && state.unsealedMarks)
    val hard = MosaicGame(setup(difficulty = Difficulty.HARD)).state
    assertTrue(!hard.unsealedMarks && hard.toleranceMm < state.toleranceMm)
  }
}
