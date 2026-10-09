package xyz.mcxross.formation.state.mining

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import xyz.mcxross.formation.longshot.LongshotInput
import xyz.mcxross.formation.longshot.LongshotPhase
import xyz.mcxross.formation.longshot.LongshotState
import xyz.mcxross.formation.longshot.LongshotTerms
import xyz.mcxross.formation.session.FormationClient
import xyz.mcxross.formation.session.FormationJson
import xyz.mcxross.formation.session.Stage
import xyz.mcxross.formation.solana.ore.OreDeposit

/** Coordinates this phone's wallet with the host-owned game; signing never runs in a composable. */
class LongshotViewModel(
  private val repository: MiningRepository,
  private val client: FormationClient,
) : ViewModel() {
  val mining = repository.state

  init {
    viewModelScope.launch {
      // Re-send a durable receipt after reconnecting or after a wallet returns to the app.
      combine(client.frame, client.snapshot, client.status, repository.state) { _, _, _, _ ->
          currentDeposit()
        }
        .collect { deposit ->
          if (deposit != null) {
            val saved =
              repository.state.value.positions.firstOrNull { it.reference == deposit.reference }
            val turn = state()?.takeIf { currentDeposit() == deposit }?.turn
            if (turn != null && saved?.signature != null && saved.status != MiningStatus.Failed)
              send(LongshotInput.Submitted(turn, saved.wallet, saved.signature, saved.validUntil))
          }
        }
    }
    viewModelScope.launch {
      repository.refresh()
      while (true) {
        delay(8_000)
        if (
          repository.state.value.awaitingDraw ||
            repository.state.value.positions.any {
              it.status == MiningStatus.Submitted || it.status == MiningStatus.Mining
            }
        )
          repository.refresh()
      }
    }
  }

  fun connect() {
    viewModelScope.launch { repository.connect() }
  }

  fun requestTestSol() {
    viewModelScope.launch { repository.requestTestSol() }
  }

  fun refresh() {
    viewModelScope.launch { repository.refresh() }
  }

  fun mine() {
    val deposit = currentDeposit() ?: return
    viewModelScope.launch { repository.mine(deposit) { currentDeposit() == deposit } }
  }

  fun settle() {
    viewModelScope.launch { repository.settle() }
  }

  private fun state(): LongshotState? =
    client.frame.value?.let {
      runCatching { FormationJson.decodeFromJsonElement(LongshotState.serializer(), it.state) }
        .getOrNull()
    }

  private fun currentDeposit(): OreDeposit? {
    val snapshot = client.snapshot.value ?: return null
    val frame = client.frame.value ?: return null
    if (snapshot.stage !is Stage.Playing || frame.round != snapshot.round) return null
    val state = state() ?: return null
    if (state.phase != LongshotPhase.Funding || state.picker != client.me.value) return null
    val wallet = repository.state.value.wallet ?: return null
    val round = state.oreRound ?: return null
    val number = state.number ?: return null
    return OreDeposit(
      LongshotTerms.reference(snapshot.formation.session, frame.round, state.turn),
      wallet,
      round,
      number,
      LongshotTerms.LAMPORTS,
    )
  }

  private fun send(input: LongshotInput) =
    client.play(FormationJson.encodeToJsonElement(LongshotInput.serializer(), input))
}
