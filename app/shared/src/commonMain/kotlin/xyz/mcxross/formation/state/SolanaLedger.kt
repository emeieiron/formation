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
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.crypto.hexToBytes
import xyz.mcxross.formation.model.Budget
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
import xyz.mcxross.formation.solana.ContestMode
import xyz.mcxross.formation.solana.EntryState
import xyz.mcxross.formation.solana.SgtFinder
import xyz.mcxross.formation.solana.VaultConfig
import xyz.mcxross.formation.solana.VaultContest
import xyz.mcxross.formation.solana.VaultEntry
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

  private val _budgets = MutableStateFlow<List<Budget>>(emptyList())
  override val budgets: StateFlow<List<Budget>> = _budgets.asStateFlow()

  private val _draws = MutableStateFlow<List<OpenDraw>>(emptyList())
  override val draws: StateFlow<List<OpenDraw>> = _draws.asStateFlow()

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
      val sgt = seeker.sgt?.let(SolanaPublicKey::from) ?: return@runCatching emptyList<Budget>() to emptyList()
      val config = config()
      val contests =
        rpc.programAccounts(vault.programId, listOf(SolanaRpc.Filter.Memcmp(0, VaultContest.DISCRIMINATOR)))
          .map { (address, data) -> VaultContest.decode(address, data) }
          .filter { it.mint == config.mint && it.sgtGroup == config.sgtGroup }
      val entries =
        rpc.programAccounts(
            vault.programId,
            listOf(SolanaRpc.Filter.Memcmp(0, VaultEntry.DISCRIMINATOR), SolanaRpc.Filter.Memcmp(VaultEntry.SGT_OFFSET, sgt.bytes)),
          )
          .map { (address, data) -> VaultEntry.decode(address, data) }
          .groupBy { it.contest }
      val now = (rpc.chainTimeMillis() ?: now()) / 1_000
      val budgets = contests.mapNotNull { budgetFor(it, sgt, entries[it.address].orEmpty(), now) }
      val draws = contests.mapNotNull { c ->
        val entered = entries[c.address].orEmpty().isNotEmpty()
        OpenDraw(c.address.base58(), Skr(c.pool.toLong()), c.drawBps, c.enterUntilSeconds * 1_000, shortKey(c.sponsor), c.title, entered)
          .takeIf { c.mode == ContestMode.DRAW && now < c.enterUntilSeconds }
      }
      budgets.sortedBy { it.playUntil } to draws.sortedBy { it.enterUntil }
    }
      .onSuccess { (budgets, draws) ->
        _budgets.value = budgets
        _draws.value = draws
        _problem.value = null
      }
      .onFailure { _problem.value = "Couldn't reach Solana: ${describe(it)}" }
  }

  // The next budget [sgt] can take from [contest], given the entries it already has there.
  private suspend fun budgetFor(contest: VaultContest, sgt: SolanaPublicKey, entries: List<VaultEntry>, nowSeconds: Long): Budget? {
    if (!contest.inPlay(nowSeconds) || (contest.only != null && contest.only != sgt)) return null
    val (address, round, drawn) = when (contest.mode) {
      ContestMode.FIRST_COME -> {
        if (contest.unallocated < contest.budget) return null
        val round = (0 until contest.winsPerSgt).firstOrNull { r -> entries.none { it.round == r } } ?: return null
        Triple(vault.entry(contest.address, sgt, round), round, false)
      }
      ContestMode.DRAW -> {
        val entry = entries.firstOrNull { it.state == EntryState.REGISTERED && contest.selects(it.index) } ?: return null
        Triple(entry.address, 0, true)
      }
    }
    return Budget(
      id = OpportunityId(address.base58()),
      contest = contest.address.base58(),
      sgt = sgt.base58(),
      amount = Skr(contest.budget.toLong()),
      ownerWeight = contest.settings.ownerWeight,
      maxGuests = contest.maxGuests,
      playUntil = contest.playUntilSeconds * 1_000,
      sponsor = shortKey(contest.sponsor),
      title = contest.title,
      round = round,
      drawn = drawn,
    )
  }

  override suspend fun enter(seeker: SeekerIdentity, draw: OpenDraw): Result<String> = attempt { settlementLock.withLock {
    val operation = "register:${draw.contest}"
    submissions.resumePending()
    journal.latest(operation)?.takeIf { it.state == SubmissionState.PENDING || it.state == SubmissionState.CONFIRMED }?.let {
      return@attempt submissions.execute(it)
    }
    val holder = holder(seeker)
    val blockhash = rpc.latestBlockhashInfo()
    val tx = transaction(holder.wallet, blockhash.value, vault.register(holder, SolanaPublicKey.from(draw.contest)))
    val signed = if (seeker.simulated) tx.signedBy(claimKey()).serialize() else walletSign(listOf(tx)).single()
    val signature = submissions.execute(journal.prepare(operation, signed, blockhash.lastValidBlockHeight))
    _draws.update { list -> list.map { if (it.contest == draw.contest) it.copy(entered = true) else it } }
    signature
  } }

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
    val budget = opportunity.budget
    val contest = SolanaPublicKey.from(budget.contest)
    val entryAddress = SolanaPublicKey.from(opportunity.id.value)
    val onChain = rpc.account(entryAddress)?.let { VaultEntry.decode(entryAddress, it.data) }
    val open = onChain == null || onChain.state == EntryState.REGISTERED
    if (!open) {
      check(onChain!!.rosterRoot.contentEquals(seal.root.hexToBytes()) &&
        onChain.rosterSize == seal.roster.size && onChain.result.contentEquals(Sealing.resultOf(seal))) {
        "The chain roster differs from the saved win"
      }
    }
    val payer = SolanaPublicKey.from(seeker.wallet)
    val mint = config().mint
    val unlock = if (!open) null else {
      val holder = holder(seeker)
      check(holder.sgt.base58() == budget.sgt) { "This reward belongs to another Seeker" }
      val root = seal.root.hexToBytes()
      if (budget.drawn) vault.unlockDrawn(holder, contest, mint, root, seal.roster.size, Sealing.resultOf(seal))
      else vault.unlock(holder, contest, mint, budget.round, root, seal.roster.size, Sealing.resultOf(seal))
    }
    val bound = seal.roster.filter { it.wallet != null }
    val paid = bound.filter { !open && onChain!!.hasClaimed(it.index) }.map { it.player }.toMutableList()
    val payouts = bound.filter { it.player !in paid }.map { share ->
      share to vault.claim(payer, SolanaPublicKey.from(share.claimKey), SolanaPublicKey.from(share.wallet!!),
        contest, entryAddress, mint, share.index, Sealing.proof(seal, share.player).orEmpty(), claimerSigns = false)
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
    _budgets.update { list -> list.filterNot { it.id == opportunity.id } }
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
    val reconciled = reconcileClaim(ticket, chainState(ticket), rpc.chainTimeMillis())
    book.update(reconciled)
    check(!reconciled.claimed && !reconciled.lapsed && reconciled.unlocked) { "This reward is no longer claimable" }
    val key = claimKey()
    val claimer = SolanaPublicKey(key.publicKey)
    val payer = SolanaPublicKey.from(recipient)
    val to = ticket.wallet?.let(SolanaPublicKey::from) ?: payer
    val blockhash = rpc.latestBlockhashInfo()
    val ix = vault.claim(payer, claimer, to, SolanaPublicKey.from(ticket.contest), SolanaPublicKey.from(ticket.opportunity.value),
      config().mint, ticket.index, ticket.proof.map { it.hexToBytes() }, claimerSigns = ticket.wallet == null)
    val tx = transaction(payer, blockhash.value, ix).let { if (ticket.wallet == null) it.signedBy(key) else it }
    val signed = if (payer == claimer) tx.serialize() else walletSign(listOf(tx)).single()
    val signature = submissions.execute(journal.prepare(operation, signed, blockhash.lastValidBlockHeight, to.base58()))
    book.claimed(ticket, to.base58(), signature)
    signature
  } }

  override suspend fun rewardProblem(opportunity: Opportunity, wallet: String): String? {
    val budget = opportunity.budget
    val contestAddress = SolanaPublicKey.from(budget.contest)
    val sgt = SolanaPublicKey.from(budget.sgt)
    val (contestAccount, entryAccount) = try {
      rpc.multipleAccounts(listOf(contestAddress, SolanaPublicKey.from(opportunity.id.value)))
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      return null
    }
    contestAccount ?: return "This Formation's reward isn't on chain."
    val contest = VaultContest.decode(contestAddress, contestAccount.data)
    val entry = entryAccount?.let { VaultEntry.decode(SolanaPublicKey.from(opportunity.id.value), it.data) }
    val shown = Budget(opportunity.id, budget.contest, budget.sgt, Skr(contest.budget.toLong()), contest.settings.ownerWeight,
      contest.maxGuests, contest.playUntilSeconds * 1_000, budget.sponsor, contest.title, budget.round, contest.mode == ContestMode.DRAW)
    val holder = try {
      SgtFinder(rpc, contest.sgtGroup).find(SolanaPublicKey.from(wallet))
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      return null
    }
    return when {
      shown != budget || vault.entry(contestAddress, sgt, budget.round) != SolanaPublicKey.from(opportunity.id.value) ->
        "This Formation's reward doesn't match what the host shows."
      holder?.mint != sgt || (contest.only != null && contest.only != sgt) -> "This Formation's reward belongs to another Seeker."
      !contest.inPlay(now() / 1_000) -> "This Formation's reward has ended."
      budget.drawn && (entry == null || !contest.selects(entry.index)) -> "This Seeker wasn't drawn for this reward."
      entry != null && entry.state == EntryState.UNLOCKED || !budget.drawn && entry != null ->
        "This Formation's reward has already been unlocked."
      !budget.drawn && (contest.unallocated < contest.budget || budget.round >= contest.winsPerSgt) ->
        "Every budget in this contest has been taken."
      else -> null
    }
  }

  override suspend fun stillLocked(opportunity: Opportunity): Boolean? = runCatching {
    val (contest, entry) = rpc.multipleAccounts(listOf(SolanaPublicKey.from(opportunity.budget.contest), SolanaPublicKey.from(opportunity.id.value)))
    when {
      entry != null -> VaultEntry.decode(SolanaPublicKey.from(opportunity.id.value), entry.data).state == EntryState.REGISTERED
      else -> contest != null && VaultContest.decode(SolanaPublicKey.from(opportunity.budget.contest), contest.data).inPlay(now() / 1_000)
    }
  }
    .getOrNull()

  // Shares sealed while the Seeker was offline become claimable, or paid, once it unlocks.
  override suspend fun sync(): Unit = settlementLock.withLock {
    submissions.resumePending()
    try {
      val observedTime = rpc.chainTimeMillis()
      for (ticket in tickets.value.filter { !it.claimed && it.index >= 0 }) {
        val chain = chainState(ticket)
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

  // An entry exists once its budget is unlocked or entered in a draw; until then its contest says how long it can be.
  private suspend fun chainState(ticket: ClaimTicket): ClaimChainState? {
    val entryAddress = SolanaPublicKey.from(ticket.opportunity.value)
    val contestAddress = SolanaPublicKey.from(ticket.contest)
    val (entry, contest) = rpc.multipleAccounts(listOf(entryAddress, contestAddress))
    val playUntil = contest?.let { VaultContest.decode(contestAddress, it.data).playUntilSeconds * 1_000 }
    return when {
      entry != null -> ClaimChainState.from(VaultEntry.decode(entryAddress, entry.data), ticket.index, playUntil)
      playUntil != null -> ClaimChainState.pending(playUntil)
      else -> null
    }
  }

  private suspend fun holder(seeker: SeekerIdentity): FormationVault.Holder {
    val wallet = SolanaPublicKey.from(seeker.wallet)
    val found = SgtFinder(rpc, config().sgtGroup).find(wallet) ?: error("This wallet no longer holds a Seeker Genesis Token")
    return FormationVault.Holder(wallet, found.mint, found.account)
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

  private fun shortKey(key: SolanaPublicKey) = key.base58().let { "${it.take(4)}…${it.takeLast(4)}" }
}
