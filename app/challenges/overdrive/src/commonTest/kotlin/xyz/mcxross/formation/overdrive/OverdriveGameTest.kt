package xyz.mcxross.formation.overdrive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.GameStatus
import xyz.mcxross.formation.session.Stat

class OverdriveGameTest {
  private val players = listOf(PlayerId("a"), PlayerId("b"))
  private fun game(difficulty: Difficulty = Difficulty.NORMAL, seed: Long = 41) =
    OverdriveGame(ChallengeSetup(players, players.first(), difficulty, seed, 1_000))

  private fun align(game: OverdriveGame, player: PlayerId, now: Long = game.state.waveAt) {
    while (game.state.dial(player).facing() != game.state.dial(player).clue) {
      game.input(player, Rotate(game.state.wave, game.state.dial(player).turns + 1), now)
    }
  }

  private fun play(difficulty: Difficulty, failed: Set<Int>): OverdriveGame {
    val game = game(difficulty)
    while (game.status == GameStatus.Running) {
      val wave = game.state.wave
      if (wave in failed) {
        val dial = game.state.dials.first()
        if (dial.facing() == dial.clue) game.input(dial.player, Rotate(wave, dial.turns + 1), game.state.waveAt)
      } else players.forEach { align(game, it) }
      game.tick(game.state.dials.maxOf { it.catchAt } + OverdriveGame.LATE_INPUT_MS)
      game.tick(game.state.nextWaveAt)
      if (game.state.wave == wave) game.tick(game.state.endsAt + OverdriveGame.LATE_INPUT_MS)
    }
    return game
  }

  @Test
  fun bothPlayersMustCatchBeforeAWaveCounts() {
    val game = game()
    players.forEach { align(game, it) }
    game.tick(game.state.dials.first().catchAt + OverdriveGame.LATE_INPUT_MS)
    assertEquals(Catch.Caught, game.state.dials.first().result)
    assertEquals(0, game.state.clears)
    game.tick(game.state.dials.last().catchAt + OverdriveGame.LATE_INPUT_MS)
    assertEquals(1, game.state.clears)
    assertEquals(0, game.state.misses)
  }

  @Test
  fun aFailedPairCostsOneMissAndThreeFailedPairsEndTheGame() {
    val game = game()
    repeat(3) { index ->
      if (index > 0) game.tick(game.state.nextWaveAt)
      game.tick(game.state.dials.maxOf { it.catchAt } + OverdriveGame.LATE_INPUT_MS)
      assertEquals(index + 1, game.state.misses)
      assertEquals(0, game.state.clears)
    }
    assertEquals(listOf(Stat("Waves", "0/12"), Stat("Misses", "3")), assertIs<GameStatus.Lost>(game.status).stats)
    val terminal = game.state
    game.input(players.first(), Rotate(game.state.wave, game.state.dials.first().turns + 1), game.state.nextWaveAt)
    game.tick(Long.MAX_VALUE)
    assertEquals(terminal, game.state)
  }

  @Test
  fun staleDuplicateSkippedUnknownAndEarlyInputsDoNotRotate() {
    val game = game()
    val initial = game.state
    game.input(players.first(), Rotate(1, 1), 999)
    game.input(PlayerId("unknown"), Rotate(1, 1), 1_000)
    game.input(players.first(), Rotate(0, 1), 1_000)
    game.input(players.first(), Rotate(1, 2), 1_000)
    assertEquals(initial, game.state)
    game.input(players.first(), Rotate(1, 1), 1_000)
    game.input(players.first(), Rotate(1, 1), 1_000)
    assertEquals(1, game.state.dials.first().turns)
    align(game, players.first())
    align(game, players.last())
    game.tick(game.state.nextWaveAt)
    val next = game.state
    game.input(players.first(), Rotate(1, next.dials.first().turns + 1), next.waveAt)
    assertEquals(next, game.state)
  }

  @Test
  fun aRotationAtTheCatchDeadlineCannotChangeTheCatch() {
    val game = game()
    val dial = game.state.dials.first()
    game.input(dial.player, Rotate(1, 1), dial.catchAt)
    game.tick(dial.catchAt + OverdriveGame.LATE_INPUT_MS)
    assertEquals(Catch.Missed, game.state.dials.first().result)
    assertEquals(0, game.state.dials.first().turns)
    val timely = game()
    align(timely, players.first(), timely.state.dials.first().catchAt - 1)
    timely.tick(timely.state.dials.first().catchAt + OverdriveGame.LATE_INPUT_MS)
    assertEquals(Catch.Caught, timely.state.dials.first().result)
  }

  @Test
  fun aTapTouchedBeforeTheDeadlineCountsIfItArrivesWithinTheLateWindow() {
    val player = players.first()
    val game = game()
    val deadline = game.state.dial(player).catchAt
    while (game.state.dial(player).facing() != game.state.dial(player).clue) {
      game.input(player, Rotate(1, game.state.dial(player).turns + 1, at = deadline - 5), deadline + OverdriveGame.LATE_INPUT_MS - 1)
    }
    assertEquals(Catch.Pending, game.state.dial(player).result)
    game.tick(deadline + OverdriveGame.LATE_INPUT_MS)
    assertEquals(Catch.Caught, game.state.dial(player).result)
  }

  @Test
  fun tapsArrivingAfterTheLateWindowOrTouchedAfterTheDeadlineAreRejected() {
    val player = players.first()
    listOf(-5L to OverdriveGame.LATE_INPUT_MS, 5L to 20L, -5_000L to OverdriveGame.LATE_INPUT_MS).forEach { (touched, arrived) ->
      val game = game()
      val deadline = game.state.dial(player).catchAt
      game.input(player, Rotate(1, 1, at = deadline + touched), deadline + arrived)
      assertEquals(0, game.state.dial(player).turns)
    }
  }

  @Test
  fun projectedViewsHideOnlyTheRecipientsCurrentAndFutureAnswers() {
    val game = game()
    players.forEach { player ->
      val view = game.stateFor(player)
      assertNull(view.dial(player).clue)
      assertNull(view.dial(player).nextClue)
      assertEquals(game.state.partner(player).clue, view.partner(player).clue)
      assertEquals(game.state.partner(player).nextClue, view.partner(player).nextClue)
      assertEquals(game.state.dial(player).edges, view.dial(player).edges)
    }
  }

  @Test
  fun everyDifficultyCanCompleteAndChangesPacingWithoutChangingControls() {
    Difficulty.entries.forEach { difficulty ->
      val game = game(difficulty)
      repeat(12) { index ->
        if (index > 0) game.tick(game.state.nextWaveAt)
        assertEquals(index >= 8, game.state.preview)
        if (index < 4) assertTrue(game.state.dials[0].catchAt < game.state.dials[1].catchAt)
        else assertEquals(game.state.dials[0].catchAt, game.state.dials[1].catchAt)
        players.forEach { player ->
          assertNotEquals(game.state.dial(player).facing(), game.state.dial(player).clue)
          align(game, player)
        }
        game.tick(game.state.dials.maxOf { it.catchAt } + OverdriveGame.LATE_INPUT_MS)
      }
      assertIs<GameStatus.Won>(game.status)
      assertEquals(12, game.state.clears)
      assertTrue(game.state.lastWave!!.at < game.state.endsAt)
    }
  }

  @Test
  fun seededGamesReplayAndLargeClockJumpsCannotCreateFreeWins() {
    val first = game()
    val second = game()
    assertEquals(first.state, second.state)
    repeat(4) { index ->
      if (index > 0) listOf(first, second).forEach { it.tick(it.state.nextWaveAt) }
      listOf(first, second).forEach { game ->
        players.forEach { align(game, it) }
        game.tick(game.state.dials.maxOf { it.catchAt } + OverdriveGame.LATE_INPUT_MS)
      }
      assertEquals(first.state, second.state)
    }
    val unattended = game()
    unattended.tick(unattended.state.endsAt + OverdriveGame.LATE_INPUT_MS)
    assertIs<GameStatus.Lost>(unattended.status)
    assertEquals(0, unattended.state.clears)
  }

  @Test
  fun everyClearedWaveSpeedsUpTheNextPulse() {
    Difficulty.entries.forEach { difficulty ->
      val flights = (0..11).map(Pacing(difficulty)::flight)
      flights.zipWithNext().forEach { (earlier, later) -> assertTrue(later <= earlier * 0.92) }
      assertTrue(flights.first().toDouble() / flights.last() >= 2.5)
    }
  }

  @Test
  fun gapsAfterClearedWavesShrinkAsTheGroupProgresses() {
    val pacing = Pacing(Difficulty.NORMAL)
    val gaps = (0..11).map { pacing.reset(true, it) }
    gaps.zipWithNext().forEach { (earlier, later) -> assertTrue(later <= earlier) }
    assertEquals(650, gaps.first())
    assertEquals(350, gaps.last())
    assertEquals(900, pacing.reset(false, 6))
  }

  @Test
  fun theClockAllowsOneEarlyFailedWaveButNotTwo() {
    Difficulty.entries.forEach { difficulty ->
      val clean = assertIs<GameStatus.Won>(play(difficulty, failed = emptySet()).status)
      assertEquals(listOf("Waves", "Misses", "Time left"), clean.stats.map { it.label })
      assertEquals(Stat("Waves", "12/12"), clean.stats.first())
      assertIs<GameStatus.Won>(play(difficulty, failed = setOf(1)).status)
      val late = assertIs<GameStatus.Lost>(play(difficulty, failed = setOf(1, 2)).status)
      assertEquals("The formation ran out of time.", late.reason)
      assertEquals(Stat("Misses", "2"), late.stats.last())
    }
  }

  @Test
  fun aClearedWaveSpeedsUpBothPulsesAndAFailedWaveKeepsThePace() {
    val pacing = Pacing(Difficulty.NORMAL)
    val game = game()
    fun falls() = game.state.dials.map { it.catchAt - it.launchAt }
    assertEquals(List(2) { pacing.flight(0) }, falls())
    val partnerDeadline = game.state.dials.last().catchAt
    align(game, players.first())
    game.tick(game.state.dials.first().catchAt + OverdriveGame.LATE_INPUT_MS)
    assertEquals(partnerDeadline, game.state.dials.last().catchAt)
    game.tick(partnerDeadline + OverdriveGame.LATE_INPUT_MS)
    game.tick(game.state.nextWaveAt)
    assertEquals(1, game.state.misses)
    assertEquals(List(2) { pacing.flight(0) }, falls())
    players.forEach { align(game, it) }
    game.tick(game.state.dials.maxOf { it.catchAt } + OverdriveGame.LATE_INPUT_MS)
    game.tick(game.state.nextWaveAt)
    assertEquals(1, game.state.clears)
    assertEquals(List(2) { pacing.flight(1) }, falls())
    game.state.dials.forEach { dial ->
      assertEquals(0f, dial.progress(dial.launchAt - 1))
      assertEquals(1f, dial.progress(dial.catchAt))
      assertTrue(dial.progress(dial.catchAt - 1) < 1f)
      assertEquals(1f, dial.progress(dial.catchAt + 1_000))
    }
  }
}
