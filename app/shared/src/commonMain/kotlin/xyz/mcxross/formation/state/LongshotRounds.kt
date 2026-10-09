package xyz.mcxross.formation.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import xyz.mcxross.formation.longshot.Longshot
import xyz.mcxross.formation.longshot.LongshotObservation
import xyz.mcxross.formation.longshot.LongshotPhase
import xyz.mcxross.formation.longshot.LongshotState
import xyz.mcxross.formation.longshot.LongshotTerms
import xyz.mcxross.formation.session.FormationClient
import xyz.mcxross.formation.session.FormationHost
import xyz.mcxross.formation.session.FormationJson
import xyz.mcxross.formation.session.Stage
import xyz.mcxross.formation.solana.ore.DepositVerification
import xyz.mcxross.formation.solana.ore.OreDeposit
import xyz.mcxross.formation.solana.ore.OreMiningObserver
import xyz.mcxross.formation.solana.ore.OreRoundResult

/** Observations enter the host event loop; player messages cannot provide ORE outcomes. */
internal class LongshotRounds(
  private val host: FormationHost,
  private val client: FormationClient,
  private val reader: OreMiningObserver,
  scope: CoroutineScope,
) {
  init {
    scope.launch {
      host.snapshot
        .map { snapshot ->
          snapshot.round.takeIf {
            snapshot.stage is Stage.Playing &&
              snapshot.formation.opportunity.challenge == Longshot.id
          }
        }
        .distinctUntilChanged()
        .collectLatest { round ->
          if (round == null) return@collectLatest
          var turn = -1
          var target: Long? = null
          while (true) {
            val frame = client.frame.value
            val state =
              frame
                ?.takeIf { it.round == round }
                ?.let {
                  FormationJson.decodeFromJsonElement(LongshotState.serializer(), it.state)
                }
            if (state != null) {
              if (turn != state.turn) {
                turn = state.turn
                target = null
              }
              if (
                state.phase in
                  setOf(
                    LongshotPhase.AwaitingRound,
                    LongshotPhase.Verifying,
                    LongshotPhase.Watching,
                  )
              ) {
                try {
                  withTimeout(12_000) {
                    if (state.phase == LongshotPhase.AwaitingRound) {
                      // Bind once, after predictions lock; retries must not select a different
                      // round.
                      val id = target ?: reader.fundingRound().also { target = it }
                      host.observe(round, LongshotObservation.Bound(turn, id))
                    } else if (state.phase == LongshotPhase.Verifying) {
                      val id = requireNotNull(state.oreRound)
                      val signature = requireNotNull(state.miningSignature)
                      val deposit = runCatching {
                        OreDeposit(
                          LongshotTerms.reference(
                            host.snapshot.value.formation.session,
                            round,
                            turn,
                          ),
                          requireNotNull(state.miningWallet),
                          id,
                          requireNotNull(state.number),
                          LongshotTerms.LAMPORTS,
                        )
                      }
                        .getOrNull()
                      if (deposit == null) {
                        host.observe(
                          round,
                          LongshotObservation.Rejected(
                            turn,
                            signature,
                            "Invalid mining wallet or terms.",
                          ),
                        )
                        return@withTimeout
                      }
                      when (
                        val proof = reader.verifyDeposit(deposit, signature, state.miningValidUntil)
                      ) {
                        DepositVerification.Pending -> Unit
                        DepositVerification.Confirmed ->
                          host.observe(round, LongshotObservation.Funded(turn, id, signature))
                        is DepositVerification.Rejected ->
                          host.observe(
                            round,
                            LongshotObservation.Rejected(turn, signature, proof.reason),
                          )
                      }
                    } else {
                      val id = requireNotNull(state.oreRound)
                      when (val result = reader.result(id)) {
                        OreRoundResult.Pending -> Unit
                        OreRoundResult.Unavailable ->
                          host.observe(round, LongshotObservation.Unresolved(turn, id))
                        is OreRoundResult.Resolved ->
                          host.observe(round, LongshotObservation.Resolved(turn, id, result.number))
                      }
                    }
                    host.observe(round, LongshotObservation.Connection(turn, true))
                  }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                  host.observe(round, LongshotObservation.Connection(turn, false))
                } catch (e: CancellationException) {
                  throw e
                } catch (_: Exception) {
                  host.observe(round, LongshotObservation.Connection(turn, false))
                }
              }
            }
            delay(3_000)
          }
        }
    }
  }
}
