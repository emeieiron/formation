package xyz.mcxross.formation.ui.mining

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import xyz.mcxross.formation.challenge.StageScope
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.longshot.LongshotInput
import xyz.mcxross.formation.longshot.LongshotPhase
import xyz.mcxross.formation.longshot.LongshotStage
import xyz.mcxross.formation.longshot.LongshotState
import xyz.mcxross.formation.longshot.LongshotTerms
import xyz.mcxross.formation.state.ActiveSession
import xyz.mcxross.formation.state.mining.LongshotViewModel
import xyz.mcxross.formation.state.mining.MiningOperation
import xyz.mcxross.formation.ui.LocalGraph

@Composable
internal fun LongshotSession(
  scope: StageScope<LongshotState, LongshotInput>,
  session: ActiveSession,
) {
  val graph = LocalGraph.current
  val vm = ownedViewModel(session) { LongshotViewModel(graph.mining, session.client) }
  val mining by vm.mining.collectAsState()
  LongshotStage(scope) {
    if (scope.state.phase == LongshotPhase.Funding && scope.me == scope.state.picker) {
      Text(
        "Test tokens have no monetary value. Keep 0.011 test SOL available for mining, account rent, and fees. Unused funds stay in your wallet.",
        style = Theme.type.footnote,
      )
      if (mining.needsAttention) MiningRecovery(mining, vm::connect, vm::refresh, vm::settle)
      else MiningWallet(mining, vm::connect, vm::requestTestSol, vm::refresh)
      if (mining.wallet != null && !mining.needsAttention)
        Button(
          "Mine with ${LongshotTerms.AMOUNT}",
          vm::mine,
          enabled =
            !mining.busy &&
              mining.balance != null &&
              mining.balance!! >= 11_000_000 &&
              !mining.pendingSubmission,
          loading = mining.operation == MiningOperation.Mining,
        )
    }
    if (scope.state.phase in setOf(LongshotPhase.Result, LongshotPhase.Skipped))
      MiningRecovery(mining, vm::connect, vm::refresh, vm::settle)
  }
}
