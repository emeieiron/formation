package xyz.mcxross.formation.state

import xyz.mcxross.formation.session.Stage
import xyz.mcxross.formation.session.Unlock

// Save the local receipt before clearing retryable work, even if the active UI has gone away.
internal class SettlementCompletion(
  private val completed: CompletedSessions,
  private val ledger: RewardLedger,
) {
  fun record(win: PendingUnlock, receipt: UnlockReceipt, seeker: SeekerIdentity, at: Long) {
    ledger.keep(
      ClaimTicket(
        win.opportunity.id,
        win.opportunity.budget.contest,
        win.opportunity.challenge,
        "Seeker",
        win.seal.ownerAmount,
        -1,
        win.seal.root,
        emptyList(),
        win.sealedAt,
        receipt.signature,
        claimedTo = seeker.wallet,
        claimReceipt = receipt.signature,
      )
    )
    completed.entries.value
      .filter { it.snapshot.formation.opportunity.id == win.opportunity.id }
      .forEach { record ->
        val won = record.snapshot.stage as? Stage.Won ?: return@forEach
        completed.remember(
          record.snapshot.copy(
            stage =
              won.copy(
                unlock =
                  Unlock.Unlocked(
                    receipt.signature,
                    at,
                    receipt.explorerUrl,
                    receipt.paid,
                    receipt.settled,
                  )
              )
          )
        )
      }
  }
}
