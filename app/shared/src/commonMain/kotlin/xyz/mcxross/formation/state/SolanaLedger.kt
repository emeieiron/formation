package xyz.mcxross.formation.state

import kotlin.coroutines.cancellation.CancellationException
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.Transaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.update
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.crypto.hexToBytes
import xyz.mcxross.formation.crypto.toHex
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.model.OpportunityId
import xyz.mcxross.formation.model.Skr
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.platform.WalletPort
import xyz.mcxross.formation.platform.WalletResult
import xyz.mcxross.formation.session.Seal
import xyz.mcxross.formation.session.Sealing
import xyz.mcxross.formation.solana.FormationVault
import xyz.mcxross.formation.solana.RpcException
import xyz.mcxross.formation.solana.SolanaRpc
import xyz.mcxross.formation.solana.VaultConfig
import xyz.mcxross.formation.solana.VaultOpportunity
import xyz.mcxross.formation.solana.VaultState
import xyz.mcxross.formation.solana.explorerUrl
import xyz.mcxross.formation.solana.signedBy
import xyz.mcxross.formation.solana.transaction
import xyz.mcxross.formation.state.settlement.*

class SolanaLedger(
  private val rpc: SolanaRpc,
  private val wallet: WalletPort,
  private val claimKey: () -> Ed25519KeyPair,
  private val cluster: String,
  store: KeyValueStore,
  private val vault: FormationVault = FormationVault(),
  private val observe: (xyz.mcxross.formation.session.DiagnosticEvent) -> Unit = {},
) : RewardLedger {
  override val mode = LedgerMode.SOLANA

  private val _opportunities = MutableStateFlow<List<Opportunity>>(emptyList())
  override val opportunities: StateFlow<List<Opportunity>> = _opportunities.asStateFlow()

  private val _problem = MutableStateFlow<String?>(null)
  override val problem: StateFlow<String?> = _problem.asStateFlow()

  private val book = TicketBook(store, "sol.tickets")
  override val tickets: StateFlow<List<ClaimTicket>> = book.tickets

  private var config: VaultConfig? = null
  private val settlementLock = Mutex()
  private val journal = SubmissionJournal(store, "sol.submissions.$cluster")
  private val submissions = SubmissionRunner(journal, RpcSubmissionTransport(rpc), observe = observe)

  override suspend fun refresh(seeker: SeekerIdentity) {
    runCatching {
      val filters =
        listOf(
          SolanaRpc.Filter.DataSize(VaultOpportunity.SIZE),
          SolanaRpc.Filter.Memcmp(VaultOpportunity.SEEKER_OFFSET, Base58.decode(seeker.wallet)),
          SolanaRpc.Filter.Memcmp(
            VaultOpportunity.STATE_OFFSET,
            byteArrayOf(VaultState.OPEN.ordinal.toByte()),
          ),
        )
      val now = now()
      rpc
        .programAccounts(vault.programId, filters)
        .map { (address, data) -> VaultOpportunity.decode(address, data) }
        .filter { it.expiresAtSeconds * 1_000 > now }
        .mapNotNull { it.toOpportunity() }
        .sortedBy { it.expiresAt }
    }
      .onSuccess {
        _opportunities.value = it
        _problem.value = null
      }
      .onFailure { _problem.value = "Couldn't reach Solana: ${describe(it)}" }
  }

  override suspend fun unlock(
    seeker: SeekerIdentity,
    opportunity: Opportunity,
    seal: Seal,
  ): Result<UnlockReceipt> = attempt { settlementLock.withLock {
    check(seal.complete) { "Everyone must seal the Formation first" }
    val prefix = opportunity.id.value
    submissions.resumePending()
    for (saved in journal.pending().filter { it.operation == "unlock:$prefix" || it.operation.startsWith("payout:$prefix:") }) {
      submissions.execute(saved)
    }
    val id = opportunity.id.bytes()
    val address = vault.opportunity(id)
    val onChain = rpc.account(address)?.let { VaultOpportunity.decode(address, it.data) }
      ?: error("This reward is no longer available")
    val payer = SolanaPublicKey.from(seeker.wallet)
    check(onChain.seeker == payer) { "This reward belongs to another Seeker" }
    val open = onChain.state == VaultState.OPEN
    if (!open) {
      check(onChain.rosterRoot.contentEquals(seal.root.hexToBytes()) &&
        onChain.rosterSize == seal.roster.size && onChain.result.contentEquals(Sealing.resultOf(seal))) {
        "The chain roster differs from the saved win"
      }
    }
    val mint = config().mint
    val unlock = if (open) vault.unlock(payer, mint, id, seal.root.hexToBytes(), seal.roster.size, Sealing.resultOf(seal)) else null
    val bound = seal.roster.filter { it.wallet != null }
    val paid = bound.filter { !open && onChain.hasClaimed(it.index) }.map { it.player }.toMutableList()
    val payouts = bound.filter { it.player !in paid }.map { share ->
      share to vault.claim(payer, SolanaPublicKey.from(share.claimKey), SolanaPublicKey.from(share.wallet!!),
        mint, id, share.index, Sealing.proof(seal, share.player).orEmpty(), claimerSigns = false)
    }
    val blockhash = rpc.latestBlockhashInfo()
    val batches = packTransactions(payer, blockhash.value, unlock, payouts)
    val signed = if (seeker.simulated) batches.map { it.transaction.signedBy(claimKey()).serialize() }
      else if (batches.isEmpty()) emptyList() else walletSign(batches.map { it.transaction })
    check(signed.size == batches.size) { "The wallet did not sign every transaction" }
    // Save all approved batches before sending any of them.
    val saved = signed.zip(batches).map { (bytes, batch) ->
      val key = if (batch.unlock) "unlock:$prefix" else "payout:$prefix:${batch.shares.joinToString(",") { it.index.toString() }}"
      journal.prepare(key, bytes, blockhash.lastValidBlockHeight)
    }
    var signature = journal.latest("unlock:$prefix")?.signature.orEmpty()
    saved.zip(batches).forEach { (entry, batch) ->
      val result = runCatching { submissions.execute(entry) }.onFailure { if (it is CancellationException) throw it }
      if (batch.unlock) signature = result.getOrThrow()
      if (result.isSuccess) paid += batch.shares.map { it.player }
    }
    _opportunities.update { list -> list.filterNot { it.id == opportunity.id } }
    UnlockReceipt(signature, signature.takeIf { it.isNotEmpty() }?.let { explorerUrl(it, cluster) }, paid, paid.containsAll(bound.map { it.player }))
  } }

  override fun keep(ticket: ClaimTicket) = book.keep(ticket)
  override fun restore(tickets: List<ClaimTicket>) = book.merge(tickets)

  override suspend fun claim(ticket: ClaimTicket, recipient: String): Result<String> = attempt { settlementLock.withLock {
    val operation = "claim:${ticket.opportunity.value}:${ticket.index}"
    submissions.resumePending()
    journal.latest(operation)?.takeIf { it.state == SubmissionState.PENDING || it.state == SubmissionState.CONFIRMED }?.let {
      val signature = submissions.execute(it)
      book.claimed(ticket, ticket.wallet ?: it.recipient ?: recipient, signature)
      return@attempt signature
    }
    val current = rpc.account(vault.opportunity(ticket.opportunity.bytes()))?.let {
      ClaimChainState.from(VaultOpportunity.decode(vault.opportunity(ticket.opportunity.bytes()), it.data), ticket.index)
    }
    val reconciled = reconcileClaim(ticket, current, rpc.chainTimeMillis())
    book.update(reconciled)
    check(!reconciled.claimed && !reconciled.lapsed && reconciled.unlocked) { "This reward is no longer claimable" }
    val key = claimKey()
    val claimer = SolanaPublicKey(key.publicKey)
    val payer = SolanaPublicKey.from(recipient)
    val to = ticket.wallet?.let(SolanaPublicKey::from) ?: payer
    val blockhash = rpc.latestBlockhashInfo()
    val ix = vault.claim(payer, claimer, to, config().mint, ticket.opportunity.bytes(), ticket.index,
      ticket.proof.map { it.hexToBytes() }, claimerSigns = ticket.wallet == null)
    val tx = transaction(payer, blockhash.value, ix).let { if (ticket.wallet == null) it.signedBy(key) else it }
    val signed = if (payer == claimer) tx.serialize() else walletSign(listOf(tx)).single()
    val signature = submissions.execute(journal.prepare(operation, signed, blockhash.lastValidBlockHeight, to.base58()))
    book.claimed(ticket, to.base58(), signature)
    signature
  } }

  override suspend fun rewardProblem(opportunity: Opportunity, wallet: String): String? {
    val address = vault.opportunity(opportunity.id.bytes())
    val account = try {
      rpc.account(address)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      return null
    } ?: return "This Formation's reward isn't on chain."
    val onChain = VaultOpportunity.decode(address, account.data)
    return when {
      onChain.seeker.base58() != wallet -> "This Formation's reward belongs to another Seeker."
      onChain.state != VaultState.OPEN -> "This Formation's reward has already been unlocked or closed."
      onChain.toOpportunity() != opportunity -> "This Formation's reward doesn't match what the host shows."
      else -> null
    }
  }

  override suspend fun stillLocked(opportunity: Opportunity): Boolean? = runCatching {
    val address = vault.opportunity(opportunity.id.bytes())
    rpc.account(address)?.let { VaultOpportunity.decode(address, it.data).state == VaultState.OPEN }
      ?: false
  }
    .getOrNull()

  // Shares sealed while the Seeker was offline become claimable, or paid, once it unlocks.
  override suspend fun sync(): Unit = settlementLock.withLock {
    submissions.resumePending()
    try {
      val observedTime = rpc.chainTimeMillis()
      for (ticket in tickets.value.filter { !it.claimed && it.index >= 0 }) {
        val address = vault.opportunity(ticket.opportunity.bytes())
        val chain = rpc.account(address)?.let { ClaimChainState.from(VaultOpportunity.decode(address, it.data), ticket.index) }
        val submission = journal.latest("claim:${ticket.opportunity.value}:${ticket.index}")
        val reconciled = if (submission?.state == SubmissionState.CONFIRMED)
          ticket.copy(unlocked = true, lapsed = false, claimedTo = ticket.wallet ?: submission.recipient ?: "another wallet", claimReceipt = submission.signature)
        else reconcileClaim(ticket, chain, observedTime, submission?.recipient)
        if (reconciled.lapsed && !ticket.lapsed) observe(xyz.mcxross.formation.session.DiagnosticEvent(xyz.mcxross.formation.session.DiagnosticCode.CLAIM_EXPIRED))
        book.update(if (reconciled.claimed && submission?.state == SubmissionState.CONFIRMED)
          reconciled.copy(claimReceipt = submission.signature) else reconciled)
      }
      _problem.value = null
    } catch (e: CancellationException) { throw e }
      catch (e: Exception) { _problem.value = "Could not update reward status: ${describe(e)}" }
  }

  private suspend fun config(): VaultConfig =
    config
      ?: VaultConfig.decode(
          rpc.account(vault.config())?.data ?: error("The Formation vault isn't set up on $cluster")
        )
        .also { config = it }

  private suspend fun walletSign(txs: List<Transaction>): List<ByteArray> =
    when (val signed = wallet.signAll(txs.map { it.serialize() })) {
      is WalletResult.Ok -> signed.value
      WalletResult.NoWallet -> error("No Solana wallet on this phone")
      is WalletResult.Failed -> error(signed.message)
    }

  private suspend fun <T> attempt(block: suspend () -> T): Result<T> = runCatching {
    block()
  }
    .onFailure { if (it is CancellationException) throw it }
    .recoverCatching { throw IllegalStateException(describe(it), it) }

  private fun describe(e: Throwable): String =
    (e as? RpcException)?.describe() ?: e.message ?: "Solana request failed"

  private fun VaultOpportunity.toOpportunity(): Opportunity? {
    val format = ChallengeCatalog.byCode(challenge) ?: return null
    return Opportunity(
      id = OpportunityId(uuidOf(id)),
      challenge = format.id,
      reward = Skr(amount.toLong()),
      players = players,
      ownerBps = ownerBps,
      difficulty = Difficulty.entries.getOrElse(difficulty) { Difficulty.NORMAL },
      expiresAt = expiresAtSeconds * 1_000,
      sponsor = sponsor.base58().let { "${it.take(4)}…${it.takeLast(4)}" },
      title = title,
    )
  }
}

internal fun uuidOf(bytes: ByteArray): String {
  val hex = bytes.toHex()
  return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}
