package xyz.mcxross.formation.state

import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import xyz.mcxross.formation.crypto.Base64
import xyz.mcxross.formation.crypto.Sha256
import xyz.mcxross.formation.crypto.toHex
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Difficulty
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

  suspend fun claim(ticket: ClaimTicket, recipient: String): Result<String>

  suspend fun sync() {}

  // Null when it can't tell, such as while offline.
  suspend fun stillLocked(opportunity: Opportunity): Boolean?
}

class TicketBook(private val store: KeyValueStore, private val key: String) {
  private val _tickets = MutableStateFlow(loadList(store, key, ClaimTicket.serializer()))
  val tickets: StateFlow<List<ClaimTicket>> = _tickets.asStateFlow()

  // A share kept while waiting for the unlock is replaced by what the unlock says; nothing else
  // changes it.
  fun keep(ticket: ClaimTicket) {
    val existing = _tickets.value.firstOrNull { it.same(ticket) }
    when {
      existing == null -> save(listOf(ticket) + _tickets.value)
      !existing.unlocked && ticket.unlocked -> update(ticket)
    }
  }

  fun update(ticket: ClaimTicket) = save(_tickets.value.map { if (it.same(ticket)) ticket else it })

  private fun ClaimTicket.same(other: ClaimTicket) =
    opportunity == other.opportunity && index == other.index

  fun claimed(ticket: ClaimTicket, to: String, receipt: String) {
    save(
      _tickets.value.map {
        if (it.opportunity == ticket.opportunity && it.index == ticket.index)
          it.copy(claimedTo = to, claimReceipt = receipt)
        else it
      }
    )
  }

  private fun save(list: List<ClaimTicket>) {
    store.putDurable(key, FormationJson.encodeToString(ListSerializer(ClaimTicket.serializer()), list))
    _tickets.value = list
  }
}

class SimulatedLedger(
  private val store: KeyValueStore,
  private val random: Random = Random.Default,
) : RewardLedger {
  override val mode = LedgerMode.SIMULATED

  private val _opportunities =
    MutableStateFlow(loadList(store, KEY_OPPORTUNITIES, Opportunity.serializer()))
  override val opportunities: StateFlow<List<Opportunity>> = _opportunities.asStateFlow()

  override val problem: StateFlow<String?> = MutableStateFlow(null)

  private val book = TicketBook(store, KEY_TICKETS)
  override val tickets: StateFlow<List<ClaimTicket>> = book.tickets

  override suspend fun refresh(seeker: SeekerIdentity) {
    val now = now()
    val live = _opportunities.value.filter { it.expiresAt > now }
    val topped =
      if (live.size >= SHELF) live
      else live + SimulatedDrops.fresh(SHELF - live.size, now, random, avoid = live)
    save(topped.sortedBy { it.expiresAt })
  }

  override suspend fun unlock(
    seeker: SeekerIdentity,
    opportunity: Opportunity,
    seal: Seal,
  ): Result<UnlockReceipt> {
    delay(1_400)
    save(_opportunities.value.filterNot { it.id == opportunity.id })
    val receipt = "sim" + Sha256.digest(Base64.decode(seal.message)).toHex().take(40)
    return Result.success(
      UnlockReceipt(receipt, null, seal.roster.filter { it.wallet != null }.map { it.player })
    )
  }

  override fun keep(ticket: ClaimTicket) = book.keep(ticket)

  override suspend fun stillLocked(opportunity: Opportunity): Boolean? =
    _opportunities.value.any { it.id == opportunity.id }

  override suspend fun claim(ticket: ClaimTicket, recipient: String): Result<String> {
    delay(900)
    val receipt =
      "sim" +
        Sha256.digest((ticket.opportunity.value + recipient).encodeToByteArray()).toHex().take(40)
    book.claimed(ticket, ticket.wallet ?: recipient, receipt)
    return Result.success(receipt)
  }

  private fun save(list: List<Opportunity>) {
    _opportunities.value = list
    store.put(
      KEY_OPPORTUNITIES,
      FormationJson.encodeToString(ListSerializer(Opportunity.serializer()), list),
    )
  }

  private companion object {
    const val KEY_OPPORTUNITIES = "sim.opportunities"
    const val KEY_TICKETS = "sim.tickets"
    const val SHELF = 9
  }
}

internal fun <T> loadList(store: KeyValueStore, key: String, serializer: KSerializer<T>): List<T> =
  store.get(key)?.let {
    runCatching { FormationJson.decodeFromString(ListSerializer(serializer), it) }.getOrNull()
  } ?: emptyList()

internal object SimulatedDrops {
  private class Drop(
    val challenge: String,
    val reward: Long,
    val players: Int,
    val ownerBps: Int,
    val difficulty: Difficulty,
    val sponsor: String,
    val days: Int,
    val title: String?,
  )

  private val drops =
    listOf(
      Drop("rally", 600, 5, 5_000, Difficulty.NORMAL, "Solana Mobile", 2, "Genesis Rally"),
      Drop("sync", 120, 2, 5_000, Difficulty.EASY, "Formation", 1, null),
      Drop("formation", 200, 2, 5_000, Difficulty.EASY, "Formation", 1, null),
      Drop("rush", 250, 2, 5_000, Difficulty.EASY, "Formation", 1, null),
      Drop("circuit", 160, 2, 5_000, Difficulty.EASY, "Formation", 1, null),
      Drop("rally", 140, 2, 5_000, Difficulty.EASY, "Formation", 1, null),
      Drop("formation", 900, 4, 4_000, Difficulty.NORMAL, "Seeker Season", 3, "Night Sky"),
      Drop("circuit", 1_500, 10, 4_000, Difficulty.HARD, "Solana Mobile", 5, "The Long Wire"),
      Drop("rush", 5_000, 20, 3_000, Difficulty.EXTREME, "Seeker Genesis", 7, "Meltdown"),
      Drop("rally", 180, 3, 5_000, Difficulty.EASY, "Formation", 1, null),
      Drop("circuit", 360, 3, 5_000, Difficulty.NORMAL, "Solana Mobile", 2, null),
      Drop("formation", 300, 3, 5_000, Difficulty.EASY, "Formation", 2, null),
    )

  fun fresh(count: Int, now: Long, random: Random, avoid: List<Opportunity>): List<Opportunity> {
    val taken = avoid.map { it.challenge.value to it.players }.toSet()
    val pool = drops.filter { (it.challenge to it.players) !in taken }.ifEmpty { drops }
    return pool.take(count).map {
      Opportunity(
        id = OpportunityId(newUuid()),
        challenge = ChallengeId(it.challenge),
        reward = Skr.of(it.reward),
        players = it.players,
        ownerBps = it.ownerBps,
        difficulty = it.difficulty,
        expiresAt = now + it.days * 86_400_000L + random.nextLong(3_600_000L),
        sponsor = it.sponsor,
        title = it.title,
      )
    }
  }
}

internal fun now(): Long = Clock.System.now().toEpochMilliseconds()
