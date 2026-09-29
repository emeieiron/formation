package xyz.mcxross.formation.state

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.Transaction
import com.solana.transaction.TransactionInstruction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import xyz.mcxross.formation.session.Share
import xyz.mcxross.formation.solana.FormationVault
import xyz.mcxross.formation.solana.RpcException
import xyz.mcxross.formation.solana.SolanaRpc
import xyz.mcxross.formation.solana.VaultConfig
import xyz.mcxross.formation.solana.VaultOpportunity
import xyz.mcxross.formation.solana.VaultState
import xyz.mcxross.formation.solana.explorerUrl
import xyz.mcxross.formation.solana.signedBy
import xyz.mcxross.formation.solana.transaction

class SolanaLedger(
  private val rpc: SolanaRpc,
  private val wallet: WalletPort,
  private val claimKey: () -> Ed25519KeyPair,
  private val cluster: String,
  store: KeyValueStore,
  private val vault: FormationVault = FormationVault(),
) : RewardLedger {
  override val mode = LedgerMode.SOLANA

  private val _opportunities = MutableStateFlow<List<Opportunity>>(emptyList())
  override val opportunities: StateFlow<List<Opportunity>> = _opportunities.asStateFlow()

  private val _problem = MutableStateFlow<String?>(null)
  override val problem: StateFlow<String?> = _problem.asStateFlow()

  private val book = TicketBook(store, "sol.tickets")
  override val tickets: StateFlow<List<ClaimTicket>> = book.tickets

  private var config: VaultConfig? = null

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
  ): Result<UnlockReceipt> = attempt {
    val payer = SolanaPublicKey.from(seeker.wallet)
    val mint = config().mint
    val id = opportunity.id.bytes()
    val unlock =
      vault.unlock(
        payer,
        mint,
        id,
        seal.root.hexToBytes(),
        seal.roster.size,
        Sealing.resultOf(seal),
      )
    val payouts =
      seal.roster
        .filter { it.wallet != null }
        .map { share ->
          val proof = Sealing.proof(seal, share.player).orEmpty()
          share to
            vault.claim(
              payer,
              SolanaPublicKey.from(share.claimKey),
              SolanaPublicKey.from(share.wallet!!),
              mint,
              id,
              share.index,
              proof,
              claimerSigns = false,
            )
        }
    val batches = pack(payer, rpc.latestBlockhash(), unlock, payouts)
    // A simulated Seeker is its claim key, so it can sign without a wallet app (emulators, local
    // validator).
    val signed =
      if (seeker.simulated) batches.map { it.first.signedBy(claimKey()).serialize() }
      else walletSign(batches.map { it.first })
    val signature = send(signed.first())
    _opportunities.update { list -> list.filterNot { it.id == opportunity.id } }
    val paid = batches.first().second.map { it.player }.toMutableList()
    signed.zip(batches).drop(1).forEach { (tx, batch) ->
      runCatching { send(tx) }.onSuccess { paid += batch.second.map { it.player } }
    }
    UnlockReceipt(signature, explorerUrl(signature, cluster), paid)
  }

  // The unlock goes first; payouts join it, then fill further transactions, up to the size limit.
  private fun pack(
    payer: SolanaPublicKey,
    blockhash: String,
    unlock: TransactionInstruction,
    payouts: List<Pair<Share, TransactionInstruction>>,
  ): List<Pair<Transaction, List<Share>>> {
    val batches =
      mutableListOf<Pair<MutableList<TransactionInstruction>, MutableList<Share>>>(
        mutableListOf(unlock) to mutableListOf()
      )
    for ((share, ix) in payouts) {
      val (ixs, shares) = batches.last()
      if (
        transaction(payer, blockhash, *(ixs + ix).toTypedArray()).serialize().size <=
          MAX_TRANSACTION_BYTES
      ) {
        ixs += ix
        shares += share
      } else {
        batches += mutableListOf(ix) to mutableListOf(share)
      }
    }
    return batches.map { (ixs, shares) ->
      transaction(payer, blockhash, *ixs.toTypedArray()) to shares
    }
  }

  override fun keep(ticket: ClaimTicket) = book.keep(ticket)

  override suspend fun claim(ticket: ClaimTicket, recipient: String): Result<String> = attempt {
    val key = claimKey()
    val claimer = SolanaPublicKey(key.publicKey)
    val payer = SolanaPublicKey.from(recipient)
    // A share bound to a wallet can only go there; the connected wallet just pays the fee.
    val to = ticket.wallet?.let(SolanaPublicKey::from) ?: payer
    val proof = ticket.proof.map { it.hexToBytes() }
    val ix =
      vault.claim(
        payer,
        claimer,
        to,
        config().mint,
        ticket.opportunity.bytes(),
        ticket.index,
        proof,
        claimerSigns = ticket.wallet == null,
      )
    val tx =
      transaction(payer, rpc.latestBlockhash(), ix).let {
        if (ticket.wallet == null) it.signedBy(key) else it
      }
    val signed = if (payer == claimer) tx.serialize() else walletSign(listOf(tx)).single()
    val signature = send(signed)
    book.claimed(ticket, to.base58(), signature)
    signature
  }

  override suspend fun stillLocked(opportunity: Opportunity): Boolean? = runCatching {
    val address = vault.opportunity(opportunity.id.bytes())
    rpc.account(address)?.let { VaultOpportunity.decode(address, it.data).state == VaultState.OPEN }
      ?: false
  }
    .getOrNull()

  // Shares sealed while the Seeker was offline become claimable, or paid, once it unlocks.
  override suspend fun sync() {
    runCatching {
      for (ticket in tickets.value.filter { !it.claimed && !it.lapsed && it.index >= 0 }) {
        val address = vault.opportunity(ticket.opportunity.bytes())
        // A reward that never unlocked and whose account is gone expired and went back to its
        // sponsor.
        val account =
          rpc.account(address)
            ?: if (!ticket.unlocked) {
              book.update(ticket.copy(lapsed = true))
              continue
            } else continue
        val onChain = VaultOpportunity.decode(address, account.data)
        if (onChain.state != VaultState.UNLOCKED) continue
        book.update(
          if (onChain.hasClaimed(ticket.index))
            ticket.copy(
              unlocked = true,
              claimedTo = ticket.wallet ?: "another wallet",
              claimReceipt = ticket.claimReceipt ?: "",
            )
          else ticket.copy(unlocked = true)
        )
      }
    }
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

  private suspend fun send(transaction: ByteArray): String =
    rpc.sendTransaction(transaction).also { rpc.confirm(it) }

  private suspend fun <T> attempt(block: suspend () -> T): Result<T> = runCatching {
    block()
  }
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

private const val MAX_TRANSACTION_BYTES = 1_232

internal fun uuidOf(bytes: ByteArray): String {
  val hex = bytes.toHex()
  return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}
