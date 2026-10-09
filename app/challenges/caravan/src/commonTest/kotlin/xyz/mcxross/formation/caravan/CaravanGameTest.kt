package xyz.mcxross.formation.caravan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.GameStatus

class CaravanGameTest {

  private val squad = (1..6).map { PlayerId("player_$it") }

  private fun setup(players: List<PlayerId> = squad, startAt: Long = 1_000L) =
    ChallengeSetup(
      players = players,
      seeker = players.first(),
      difficulty = Difficulty.NORMAL,
      seed = 42L,
      startAt = startAt,
    )

  @Test
  fun registrationPreservesGroupSizesAndHasAnIndependentWireCode() {
    assertEquals((6..16).toSet(), Caravan.info.groupSizes)
    assertEquals(10, Caravan.info.code)
    assertTrue(Caravan.info.difficulties.isEmpty())
    assertTrue(Caravan.goal(6, Difficulty.NORMAL).contains("steps each"))
  }

  @Test
  fun progressFollowsTheRearAndLaggingStartsAtFourSteps() {
    val game = CaravanGame(setup())
    val leader = squad.first()
    for (step in 1..3) game.input(leader, Stride(step, 1_000L + step * 400L), 1_000L + step * 400L)
    assertEquals(WalkerStatus.Pacing, game.state.walker(squad[1]).status)
    game.input(leader, Stride(4, 2_600L), 2_600L)
    assertEquals(WalkerStatus.Lagging, game.state.walker(squad[1]).status)
    assertEquals(0f, game.state.progress)
    squad.drop(1).forEach { game.input(it, Stride(1, 2_600L), 2_600L) }
    assertEquals(1f / 15_000, game.state.progress)
  }

  @Test
  fun targetStepsIsHardcodedTo15K() {
    val game = CaravanGame(setup())
    assertEquals(15_000, game.state.targetSteps)
    assertEquals(15_000, Pacing.TARGET_STEPS)
  }

  @Test
  fun requiresAtLeastSixPlayers() {
    val fivePlayers = (1..5).map { PlayerId("p$it") }
    assertFailsWith<IllegalArgumentException> { CaravanGame(setup(players = fivePlayers)) }
  }

  @Test
  fun debouncesStepsArrivingTooFast() {
    val game = CaravanGame(setup())
    val p1 = squad.first()

    // First step at 1500ms
    game.input(p1, Stride(1, 1_500L), 1_500L)
    assertEquals(1, game.state.walker(p1).steps)

    // Second step too fast (100ms later) -> should be debounced
    game.input(p1, Stride(2, 1_600L), 1_600L)
    assertEquals(1, game.state.walker(p1).steps)

    // Third step after debounce threshold (400ms later) -> accepted
    game.input(p1, Stride(2, 2_000L), 2_000L)
    assertEquals(2, game.state.walker(p1).steps)
  }

  @Test
  fun packRulePreventsLeadWalkerFromGettingTooFarAhead() {
    val game = CaravanGame(setup())
    val leader = squad.first()

    var time = 2_000L
    // Take steps up to MAX_SPREAD_STEPS (30)
    for (i in 1..Pacing.MAX_SPREAD_STEPS) {
      game.input(leader, Stride(i, time), time)
      time += 400L
    }

    assertEquals(Pacing.MAX_SPREAD_STEPS, game.state.walker(leader).steps)

    // Next step would exceed spread (30 vs 0) -> should be paused
    game.input(leader, Stride(Pacing.MAX_SPREAD_STEPS + 1, time), time)
    assertEquals(Pacing.MAX_SPREAD_STEPS, game.state.walker(leader).steps)
    assertEquals(WalkerStatus.WaitingForCaravan, game.state.walker(leader).status)
    assertTrue(game.state.pausedByPackRule)

    // When trailing players take steps, leader can advance again
    for (player in squad.drop(1)) {
      game.input(player, Stride(1, time), time)
    }

    time += 400L
    game.input(leader, Stride(Pacing.MAX_SPREAD_STEPS + 1, time), time)
    assertEquals(Pacing.MAX_SPREAD_STEPS + 1, game.state.walker(leader).steps)
  }

  @Test
  fun caravanWinsWhenAllPlayersReach15KSteps() {
    val game = CaravanGame(setup())
    val target = 15_000

    var time = 2_000L
    for (step in 1..target) {
      for (player in squad) {
        game.input(player, Stride(step, time), time)
      }
      time += 350L
    }

    assertIs<GameStatus.Won>(game.status)
    val won = game.status as GameStatus.Won
    assertEquals("Caravan Reached Destination!", won.headline)
  }

  @Test
  fun caravanLosesWhenTimeExpiresBeforeGoal() {
    val game = CaravanGame(setup())

    // Advance clock past endsAt without hitting target
    game.tick(game.state.endsAt + 1_000L)

    assertIs<GameStatus.Lost>(game.status)
    val lost = game.status as GameStatus.Lost
    assertEquals("Caravan ran out of time", lost.reason)
  }

  @Test
  fun duplicateAndOutOfOrderStridesDoNotCountAgain() {
    val game = CaravanGame(setup())
    val player = squad.first()
    game.input(player, Stride(1, 1_500L), 1_500L)
    game.input(player, Stride(1, 1_500L), 2_000L)
    game.input(player, Stride(0, 2_400L), 2_400L)
    assertEquals(1, game.state.walker(player).steps)
    // An index gap records one physical stride, not the number claimed by the sender.
    game.input(player, Stride(20, 2_800L), 2_800L)
    assertEquals(2, game.state.walker(player).steps)
    assertEquals(20, game.state.walker(player).lastStrideIndex)
    game.input(player, Stride(2, 3_200L), 3_200L)
    assertEquals(2, game.state.walker(player).steps)
  }

  @Test
  fun finalStridesAtTheDeadlineLoseWithoutWaitingForATimerTick() {
    val game = CaravanGame(setup())
    var at = 2_000L
    for (step in 1 until Pacing.TARGET_STEPS) {
      squad.forEach { game.input(it, Stride(step, at), at) }
      at += 350L
    }
    squad.forEach {
      game.input(it, Stride(Pacing.TARGET_STEPS, game.state.endsAt), game.state.endsAt)
    }
    assertIs<GameStatus.Lost>(game.status)
    assertEquals(Pacing.TARGET_STEPS - 1, game.state.minSteps)
    game.input(
      squad.first(),
      Stride(Pacing.TARGET_STEPS + 1, game.state.endsAt + 500L),
      game.state.endsAt + 500L,
    )
    assertEquals(Pacing.TARGET_STEPS - 1, game.state.maxSteps)
  }

  @Test
  fun localStridesRemainDistinctWithDelayedFramesAndRejectedAttempts() {
    val sequence = StrideSequence()
    val walker = WalkerState(squad.first())
    assertEquals(1, sequence.next(walker, 1_500L).stepIndex)
    assertEquals(2, sequence.next(walker, 2_000L).stepIndex)
    assertEquals(3, sequence.next(walker.copy(steps = 1, lastStrideIndex = 1), 2_500L).stepIndex)
    assertEquals(11, sequence.next(walker.copy(steps = 2, lastStrideIndex = 10), 3_000L).stepIndex)
    val state =
      CaravanGame(setup())
        .state
        .copy(walkers = listOf(walker.copy(steps = 2, lastStrideIndex = 10)))
    assertEquals(11, Caravan.autopilot(state, squad.first(), 4_000L)!!.input.stepIndex)
  }

  @Test
  fun autopilotGeneratesValidMoves() {
    val game = CaravanGame(setup())
    val p1 = squad.first()
    val move = Caravan.autopilot(game.state, p1, 2_000L)
    assertNotNull(move)
    assertEquals(1, move.input.stepIndex)
  }
}
