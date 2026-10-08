package xyz.mcxross.formation.state

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.mcxross.formation.model.Budget
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.model.OpportunityId
import xyz.mcxross.formation.model.Skr
import xyz.mcxross.formation.session.FormationInfo
import xyz.mcxross.formation.session.RoundResult
import xyz.mcxross.formation.session.Seal
import xyz.mcxross.formation.session.SessionSnapshot
import xyz.mcxross.formation.session.Stage
import xyz.mcxross.formation.session.Unlock

class PendingUnlockRetryTest {
  private val opportunity =
    Opportunity(
      Budget(OpportunityId("entry"), "contest", "sgt", Skr.of(300), 3, 1, Long.MAX_VALUE, "Test"),
      ChallengeId("overdrive"),
      2,
    )
  private val seal = Seal(emptyList(), Skr.of(225), "root", "message", emptyList())
  private val pending = PendingUnlock(opportunity, seal, 100)

  private fun active(unlock: Unlock) =
    SessionSnapshot(
      FormationInfo("session", "CODE", "Maya", opportunity),
      emptyList(),
      Stage.Won(RoundResult("Overdrive complete", endedAt = 100), seal, unlock),
      1,
    )

  @Test
  fun freshActiveWinWaitsForTheHostAndDoesNotQueueAnotherUnlockDuringSubmission() {
    assertFalse(pending.canRetryAutomatically(active(Unlock.Waiting)))
    assertFalse(pending.canRetryAutomatically(active(Unlock.Unlocking)))
  }

  @Test
  fun failedUnlockAndRemainingWalletPaymentsStillRecover() {
    assertTrue(pending.canRetryAutomatically(active(Unlock.Failed("Offline"))))
    assertTrue(
      pending.canRetryAutomatically(active(Unlock.Unlocked("receipt", 200, settled = false)))
    )
  }

  @Test
  fun restartOrAnotherActiveFormationDoesNotBlockASavedWin() {
    assertTrue(pending.canRetryAutomatically(null))
    val other =
      active(Unlock.Waiting).let {
        it.copy(
          formation =
            it.formation.copy(
              opportunity =
                opportunity.copy(
                  budget = opportunity.budget.copy(id = OpportunityId("another-entry"))
                )
            )
        )
      }
    assertTrue(pending.canRetryAutomatically(other))
  }
}
