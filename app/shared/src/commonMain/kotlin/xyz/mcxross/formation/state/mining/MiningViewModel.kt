package xyz.mcxross.formation.state.mining

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Wallet/recovery presentation. The repository owns network and persistence operations. */
class MiningViewModel(private val repository: MiningRepository) : ViewModel() {
  val state = repository.state

  init {
    viewModelScope.launch {
      if (state.value.wallet != null || state.value.needsAttention) repository.refresh()
      while (true) {
        delay(8_000)
        if (
          state.value.awaitingDraw ||
            state.value.positions.any {
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

  fun refresh() {
    viewModelScope.launch { repository.refresh() }
  }

  fun requestTestSol() {
    viewModelScope.launch { repository.requestTestSol() }
  }

  fun settle() {
    viewModelScope.launch { repository.settle() }
  }
}
