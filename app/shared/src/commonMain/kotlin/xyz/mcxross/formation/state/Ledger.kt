package xyz.mcxross.formation.state

import kotlin.time.Clock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.model.OpportunityId
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.model.Skr
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.session.FormationJson
import xyz.mcxross.formation.session.Seal

enum class LedgerMode(val label: String) {
  SIMULATED("Simulated"),
  SOLANA("Solana"),
}

@Serializable
data class ClaimTicket(
  val opportunity: OpportunityId,
  val challenge: ChallengeId,
  val host: String,
  val amount: Skr,
  // -1 is the Seeker's own share, paid when it unlocks.
  val index: Int,
  val root: String,
  val proof: List<String>,
  val earnedAt: Long,
  val unlockReceipt: String,
  val wallet: String? = null,
  val claimedTo: String? = null,
  val claimReceipt: String? = null,
  val unlocked: Boolean = true,
  val lapsed: Boolean = false,
  val claimDeadline: Long? = null,
) {
  val claimed: Boolean
    get() = claimedTo != null
}

data class UnlockReceipt(
  val signature: String,
  val explorerUrl: String?,
  val paid: List<PlayerId> = emptyList(),
  val settled: Boolean = true,
)

interface RewardLedger {
  val mode: LedgerMode

  val opportunities: StateFlow<List<Opportunity>>

  val problem: StateFlow<String?>

  suspend fun refresh(seeker: SeekerIdentity)

  suspend fun unlock(
    seeker: SeekerIdentity,
    opportunity: Opportunity,
    seal: Seal,
  ): Result<UnlockReceipt>

  val tickets: StateFlow<List<ClaimTicket>>

  fun keep(ticket: ClaimTicket)

  fun restore(tickets: List<ClaimTicket>) { tickets.forEach(::keep) }

  suspend fun claim(ticket: ClaimTicket, recipient: String): Result<String>

  suspend fun sync() {}

  // Null when it can't tell, such as while offline.
  suspend fun stillLocked(opportunity: Opportunity): Boolean?

  // Why [opportunity], as a host shows it, isn't an open reward for [wallet] on chain. Null when it is, and
  // when this phone can't reach the chain to tell; the guest's claim checks again later.
  suspend fun rewardProblem(opportunity: Opportunity, wallet: String): String? = null
}


internal fun <T> loadList(store: KeyValueStore, key: String, serializer: KSerializer<T>): List<T> =
  store.get(key)?.let {
    runCatching { FormationJson.decodeFromString(ListSerializer(serializer), it) }.getOrNull()
  } ?: emptyList()

internal fun now(): Long = Clock.System.now().toEpochMilliseconds()
