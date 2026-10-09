package xyz.mcxross.formation.longshot

import kotlin.random.Random
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeGame
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.GameObservation
import xyz.mcxross.formation.session.GameStatus

class LongshotGame(private val setup: ChallengeSetup) :
  ChallengeGame<LongshotState, LongshotInput> {
  private val random = Random(setup.seed)
  override var state = choosing(1, setup.startAt)
    private set

  override val status: GameStatus = GameStatus.Running

  init {
    require(setup.players.size in 2..8 && setup.players.distinct().size == setup.players.size)
    require(setup.seeker in setup.players)
  }

  override fun stateFor(player: PlayerId): LongshotState =
    if (state.phase == LongshotPhase.Predicting)
      state.copy(predictions = state.predictions.filterKeys { it == player })
    else state

  override fun input(from: PlayerId, input: LongshotInput, now: Long) {
    if (from !in setup.players || input.turn != state.turn || now < setup.startAt) return
    tick(now)
    when (input) {
      is LongshotInput.Pick -> {
        if (state.phase != LongshotPhase.Choosing || from != state.picker || input.number !in 1..25)
          return
        state =
          state.copy(
            phase = LongshotPhase.Predicting,
            number = input.number,
            deadline = now + PREDICT_MS,
          )
      }
      is LongshotInput.Predict -> {
        if (
          state.phase != LongshotPhase.Predicting || from == state.picker || from in state.predicted
        )
          return
        state =
          state.copy(
            predictions = state.predictions + (from to input.prediction),
            predicted = state.predicted + from,
          )
        if (state.predicted.size == setup.players.size - 1) lock()
      }
      is LongshotInput.Submitted -> {
        if (
          from != state.picker ||
            state.phase != LongshotPhase.Funding ||
            input.wallet.length !in 32..44 ||
            input.signature.length !in 80..90 ||
            input.validUntil <= 0
        )
          return
        state =
          state.copy(
            phase = LongshotPhase.Verifying,
            miningWallet = input.wallet,
            miningSignature = input.signature,
            miningValidUntil = input.validUntil,
            message = null,
          )
      }
      is LongshotInput.Next -> {
        if (
          from != setup.seeker || state.phase !in setOf(LongshotPhase.Result, LongshotPhase.Skipped)
        )
          return
        state = choosing(state.turn + 1, now)
      }
      is LongshotInput.Skip -> {
        if (
          from != setup.seeker ||
            state.phase !in
              setOf(
                LongshotPhase.AwaitingRound,
                LongshotPhase.Funding,
                LongshotPhase.Verifying,
                LongshotPhase.Watching,
              )
        )
          return
        skip("The host skipped this round.")
      }
    }
  }

  override fun tick(now: Long) {
    if (now < state.deadline) return
    when (state.phase) {
      LongshotPhase.Choosing -> skip("No number was picked in time.")
      LongshotPhase.Predicting -> lock()
      else -> Unit
    }
  }

  override fun observe(observation: GameObservation, now: Long) {
    val event = observation as? LongshotObservation ?: return
    if (event.turn != state.turn) return
    when (event) {
      is LongshotObservation.Bound ->
        if (state.phase == LongshotPhase.AwaitingRound && event.round > 0)
          state =
            state.copy(phase = LongshotPhase.Funding, oreRound = event.round, connected = true)
      is LongshotObservation.Funded ->
        if (
          state.phase == LongshotPhase.Verifying &&
            event.round == state.oreRound &&
            event.signature == state.miningSignature
        )
          state = state.copy(phase = LongshotPhase.Watching, connected = true)
      is LongshotObservation.Rejected ->
        if (state.phase == LongshotPhase.Verifying && event.signature == state.miningSignature)
          skip("Couldn't confirm this mining transaction.")
      is LongshotObservation.Resolved ->
        if (
          state.phase == LongshotPhase.Watching &&
            event.round == state.oreRound &&
            event.number in 1..25
        )
          state =
            state.copy(phase = LongshotPhase.Result, winningNumber = event.number, connected = true)
      is LongshotObservation.Unresolved ->
        if (state.phase == LongshotPhase.Watching && event.round == state.oreRound)
          skip("ORE did not publish a winning number for this round.")
      is LongshotObservation.Connection ->
        if (
          state.phase in
            setOf(
              LongshotPhase.AwaitingRound,
              LongshotPhase.Funding,
              LongshotPhase.Verifying,
              LongshotPhase.Watching,
            )
        )
          state = state.copy(connected = event.available)
    }
  }

  private fun choosing(turn: Int, now: Long) =
    LongshotState(
      turn = turn,
      picker = setup.players[random.nextInt(setup.players.size)],
      phase = LongshotPhase.Choosing,
      deadline = now + PICK_MS,
    )

  private fun lock() {
    state = state.copy(phase = LongshotPhase.AwaitingRound, deadline = 0)
  }

  private fun skip(message: String) {
    state = state.copy(phase = LongshotPhase.Skipped, message = message)
  }

  companion object {
    const val PICK_MS = 30_000L
    const val PREDICT_MS = 20_000L
  }
}
