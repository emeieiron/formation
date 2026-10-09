package xyz.mcxross.formation.longshot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.FormationJson
import xyz.mcxross.formation.session.GameStatus

class LongshotGameTest {
  private val players = listOf(PlayerId("host"), PlayerId("a"), PlayerId("b"))

  private fun game(seed: Long = 7) =
    LongshotGame(ChallengeSetup(players, players[0], Difficulty.NORMAL, seed, 1000))

  private fun picked(game: LongshotGame, number: Int = 17) {
    game.input(game.state.picker, LongshotInput.Pick(game.state.turn, number), 1000)
  }

  private fun locked(game: LongshotGame) {
    picked(game)
    players
      .filter { it != game.state.picker }
      .forEach {
        game.input(it, LongshotInput.Predict(game.state.turn, Prediction.WIN), 2000)
      }
  }

  private val wallet = "11111111111111111111111111111111"
  private val signature = "s".repeat(88)

  private fun funded(game: LongshotGame) {
    game.observe(LongshotObservation.Bound(1, 42), 3000)
    game.input(game.state.picker, LongshotInput.Submitted(1, wallet, signature, 500), 3000)
    game.observe(LongshotObservation.Funded(1, 42, signature), 3000)
  }

  @Test
  fun miningRequiresThePickersReceiptAndTrustedVerification() {
    val game = game()
    locked(game)
    game.observe(LongshotObservation.Bound(1, 42), 3000)
    assertEquals(LongshotPhase.Funding, game.state.phase)
    game.observe(LongshotObservation.Resolved(1, 42, 17), 3000)
    assertNull(game.state.outcome)
    val other = players.first { it != game.state.picker }
    game.input(other, LongshotInput.Submitted(1, wallet, signature, 500), 3000)
    assertEquals(LongshotPhase.Funding, game.state.phase)
    game.input(game.state.picker, LongshotInput.Submitted(1, wallet, signature, 500), 3000)
    assertEquals(LongshotPhase.Verifying, game.state.phase)
    game.observe(LongshotObservation.Funded(1, 43, signature), 3001)
    game.observe(LongshotObservation.Funded(1, 42, "wrong"), 3001)
    assertEquals(LongshotPhase.Verifying, game.state.phase)
    game.observe(LongshotObservation.Funded(1, 42, signature), 3002)
    assertEquals(LongshotPhase.Watching, game.state.phase)
  }

  @Test
  fun rejectedOrExpiredDeploymentDoesNotCountAsALoss() {
    val game = game()
    locked(game)
    game.observe(LongshotObservation.Bound(1, 42), 3000)
    game.input(game.state.picker, LongshotInput.Submitted(1, wallet, signature, 500), 3000)
    game.observe(LongshotObservation.Rejected(1, signature, "Expired"), 3001)
    assertEquals(LongshotPhase.Skipped, game.state.phase)
    assertNull(game.state.outcome)
  }

  @Test
  fun selectionIsSeededAndCanChooseEveryPlayer() {
    assertEquals(game().state.picker, game().state.picker)
    assertEquals(players.toSet(), (0L..100L).map { game(it).state.picker }.toSet())
  }

  @Test
  fun onlyTheSelectedPlayerCanPickOnceWithinTheRangeAndDeadline() {
    val game = game()
    val other = players.first { it != game.state.picker }
    game.input(other, LongshotInput.Pick(1, 17), 1000)
    game.input(game.state.picker, LongshotInput.Pick(1, 0), 1000)
    game.input(game.state.picker, LongshotInput.Pick(1, 26), 1000)
    game.input(game.state.picker, LongshotInput.Pick(1, 17), 999)
    assertNull(game.state.number)
    picked(game)
    game.input(game.state.picker, LongshotInput.Pick(1, 2), 1100)
    assertEquals(17, game.state.number)
    assertEquals(LongshotPhase.Predicting, game.state.phase)
  }

  @Test
  fun predictionsStayPrivateUntilEveryCallIsLocked() {
    val game = game()
    picked(game)
    val voters = players.filter { it != game.state.picker }
    game.input(game.state.picker, LongshotInput.Predict(1, Prediction.WIN), 2000)
    game.input(PlayerId("outsider"), LongshotInput.Predict(1, Prediction.WIN), 2000)
    assertTrue(game.state.predicted.isEmpty())
    game.input(voters[0], LongshotInput.Predict(1, Prediction.WIN), 2000)
    assertEquals(setOf(voters[0]), game.stateFor(voters[1]).predicted)
    assertTrue(game.stateFor(voters[1]).predictions.isEmpty())
    assertEquals(Prediction.WIN, game.stateFor(voters[0]).predictions[voters[0]])
    game.input(voters[0], LongshotInput.Predict(1, Prediction.LOSE), 2001)
    assertEquals(Prediction.WIN, game.state.predictions[voters[0]])
    game.input(voters[1], LongshotInput.Predict(1, Prediction.LOSE), 2002)
    assertEquals(LongshotPhase.AwaitingRound, game.state.phase)
    assertEquals(2, game.stateFor(game.state.picker).predictions.size)
    assertNull(game.state.oreRound)
  }

  @Test
  fun expiredChoicesAreNotAcceptedAndMissingPredictionsAreNotInvented() {
    val game = game()
    game.input(game.state.picker, LongshotInput.Pick(1, 1), 1000 + LongshotGame.PICK_MS)
    assertEquals(LongshotPhase.Skipped, game.state.phase)
    assertNull(game.state.number)
    val voting = game()
    picked(voting)
    val voter = players.first { it != voting.state.picker }
    voting.input(voter, LongshotInput.Predict(1, Prediction.LOSE), 1000 + LongshotGame.PREDICT_MS)
    assertEquals(LongshotPhase.AwaitingRound, voting.state.phase)
    assertTrue(voting.state.predictions.isEmpty())
  }

  @Test
  fun resultIsAcceptedOnlyForTheBoundRoundAndCannotBeChanged() {
    val game = game()
    game.observe(LongshotObservation.Bound(1, 42), 1000)
    assertNull(game.state.oreRound)
    locked(game)
    game.observe(LongshotObservation.Resolved(1, 42, 17), 3000)
    assertNull(game.state.winningNumber)
    funded(game)
    game.observe(LongshotObservation.Bound(1, 43), 3001)
    game.observe(LongshotObservation.Resolved(1, 41, 17), 3002)
    game.observe(LongshotObservation.Resolved(1, 42, 26), 3002)
    assertNull(game.state.winningNumber)
    assertEquals(42L, game.state.oreRound)
    game.observe(LongshotObservation.Resolved(1, 42, 17), 3003)
    assertEquals(Prediction.WIN, game.state.outcome)
    game.observe(LongshotObservation.Resolved(1, 42, 2), 3004)
    assertEquals(Prediction.WIN, game.state.outcome)
    assertEquals(
      GameStatus.Running,
      game.status,
    ) // Results stay in the game, without a reward seal.
  }

  @Test
  fun mismatchedNumberLosesAndUnavailableRoundDoesNot() {
    val game = game()
    locked(game)
    funded(game)
    game.observe(LongshotObservation.Connection(1, false), 3100)
    assertEquals(LongshotPhase.Watching, game.state.phase)
    assertFalse(game.state.connected)
    game.observe(LongshotObservation.Resolved(1, 42, 18), 4000)
    assertEquals(Prediction.LOSE, game.state.outcome)
    val missing = game()
    locked(missing)
    funded(missing)
    missing.observe(LongshotObservation.Unresolved(1, 42), 4000)
    assertEquals(LongshotPhase.Skipped, missing.state.phase)
    assertNull(missing.state.outcome)
  }

  @Test
  fun onlyHostCanReplayAndOldInputsAndObservationsCannotAffectAnotherTurn() {
    val game = game()
    locked(game)
    val guest = players[1]
    game.input(guest, LongshotInput.Skip(1), 3000)
    assertEquals(LongshotPhase.AwaitingRound, game.state.phase)
    game.input(players[0], LongshotInput.Skip(1), 3000)
    game.input(guest, LongshotInput.Next(1), 3001)
    assertEquals(1, game.state.turn)
    game.input(players[0], LongshotInput.Next(1), 3001)
    assertEquals(2, game.state.turn)
    game.input(game.state.picker, LongshotInput.Pick(1, 17), 4000)
    game.observe(LongshotObservation.Bound(1, 42), 4000)
    game.observe(LongshotObservation.Resolved(1, 42, 17), 4000)
    assertNull(game.state.number)
    assertNull(game.state.oreRound)
    assertNull(game.state.outcome)
  }

  @Test
  fun resultCannotBeSubmittedAsAPlayerInputAndStateRoundTrips() {
    assertFails {
      FormationJson.decodeFromString(
        LongshotInput.serializer(),
        """{"t":"Resolved","turn":1,"round":42,"number":17}""",
      )
    }
    val game = game()
    locked(game)
    val encoded = FormationJson.encodeToString(LongshotState.serializer(), game.state)
    assertEquals(game.state, FormationJson.decodeFromString(LongshotState.serializer(), encoded))
  }
}
