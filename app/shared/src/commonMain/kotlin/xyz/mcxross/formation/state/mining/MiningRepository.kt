package xyz.mcxross.formation.state.mining

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.LegacyMessage
import com.solana.transaction.Message
import com.solana.transaction.Transaction
import com.solana.transaction.TransactionInstruction
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import xyz.mcxross.formation.crypto.Ed25519
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.platform.WalletPort
import xyz.mcxross.formation.platform.WalletResult
import xyz.mcxross.formation.solana.ore.DepositVerification
import xyz.mcxross.formation.solana.ore.OreDeposit
import xyz.mcxross.formation.solana.ore.OreMiningRpc
import xyz.mcxross.formation.solana.ore.OreRoundResult
import xyz.mcxross.formation.solana.transaction
import xyz.mcxross.formation.state.now
import xyz.mcxross.formation.state.settlement.RpcSubmissionTransport
import xyz.mcxross.formation.state.settlement.SubmissionJournal
import xyz.mcxross.formation.state.settlement.SubmissionRunner
import xyz.mcxross.formation.state.settlement.SubmissionState

/** App-scoped state survives room and screen lifetimes. Amounts are always integer base units. */
enum class MiningOperation {
  Connecting,
  Funding,
  Mining,
  Recovering,
  Refreshing,
}

data class MiningAccountState(
  val wallet: String? = null,
  val balance: Long? = null,
  val claimableSol: ULong = 0u,
  val claimableOre: ULong = 0u,
  val needsCheckpoint: Boolean = false,
  val awaitingDraw: Boolean = false,
  val positions: List<MiningPosition> = emptyList(),
  val operation: MiningOperation? = null,
  val message: String? = null,
  val refreshFailed: Boolean = false,
  val faucetAvailable: Boolean = false,
) {
  val busy: Boolean
    get() = operation != null

  val canRecover: Boolean
    get() = !awaitingDraw && (claimableSol > 0u || claimableOre > 0u || needsCheckpoint)

  val pendingSubmission: Boolean
    get() = positions.any { it.wallet == wallet && it.status == MiningStatus.Submitted }

  val outstandingPositions: List<MiningPosition>
    get() = positions.filter {
      it.signature != null &&
        it.status in setOf(MiningStatus.Submitted, MiningStatus.Mining, MiningStatus.Resolved)
    }

  val needsAttention: Boolean
    get() = canRecover || awaitingDraw || outstandingPositions.isNotEmpty()
}

interface MiningRepository {
  val state: StateFlow<MiningAccountState>

  suspend fun connect()

  suspend fun refresh()

  suspend fun mine(deposit: OreDeposit, stillActive: () -> Boolean)

  suspend fun settle()

  suspend fun requestTestSol()
}

class DevnetMiningRepository(
  private val chain: OreMiningRpc,
  private val wallet: WalletPort,
  private val preferences: KeyValueStore,
) : MiningRepository {
  private val book = MiningStore(preferences)
  private val journal = SubmissionJournal(preferences, "ore.devnet.submissions.v1")
  private val runner = SubmissionRunner(journal, RpcSubmissionTransport(chain.client.rpc))
  private val mutex = Mutex()
  private val mutable =
    MutableStateFlow(
      MiningAccountState(
        wallet = preferences.get(WALLET),
        positions = book.positions,
      )
    )
  override val state = mutable.asStateFlow()

  override suspend fun connect() =
    operation(MiningOperation.Connecting) {
      chain.verify()
      val account = wallet.connect().value()
      preferences.putDurable(WALLET, account.address)
      mutable.value =
        mutable.value.copy(
          wallet = account.address,
          balance = null,
          claimableSol = 0u,
          claimableOre = 0u,
          needsCheckpoint = false,
          awaitingDraw = false,
          faucetAvailable = false,
        )
      refreshAccount()
    }

  override suspend fun requestTestSol() =
    operation(MiningOperation.Funding) {
      chain.verify()
      val address = requireWallet()
      val previous = preferences.get(AIRDROP_AT)?.toLongOrNull() ?: 0
      check(now() - previous >= 60_000) { "Wait one minute before requesting more test SOL." }
      // Persist before the request: an RPC timeout may still have delivered the airdrop.
      preferences.putDurable(AIRDROP_AT, now().toString())
      try {
        val signature =
          chain.client.rpc.requestAirdrop(SolanaPublicKey.from(address), 1_000_000_000)
        chain.client.rpc.confirm(signature)
        mutable.value =
          mutable.value.copy(message = "1 test SOL received.", faucetAvailable = false)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        mutable.value =
          mutable.value.copy(
            message = "Couldn't confirm funding. Refresh your balance or use the faucet.",
            faucetAvailable = true,
          )
      }
      refreshAccount()
    }

  override suspend fun mine(deposit: OreDeposit, stillActive: () -> Boolean) =
    operation(MiningOperation.Mining) {
      chain.verify()
      check(requireWallet() == deposit.wallet) { "Connect the wallet selected for this turn." }
      val existing = journal.latest(deployOperation(deposit.reference))
      if (
        existing != null &&
          existing.state in setOf(SubmissionState.PENDING, SubmissionState.CONFIRMED)
      ) {
        runner.execute(existing)
        refreshPositions()
        return@operation
      }
      check(stillActive()) { "This turn is no longer accepting mining." }
      chain.checkDeposit(deposit)
      val position =
        MiningPosition(
          deposit.reference,
          deposit.wallet,
          deposit.round,
          deposit.number,
          deposit.lamports,
        )
      book.save(position)
      publish()
      submit(
        deployOperation(deposit.reference),
        deposit.wallet,
        chain.depositInstructions(deposit),
        beforeSend = {
          check(stillActive()) {
            "The room moved on while the wallet was open. Nothing was submitted."
          }
          chain.checkDeposit(deposit)
        },
        saved = { signature, until ->
          book.save(
            position.copy(
              signature = signature,
              validUntil = until,
              status = MiningStatus.Submitted,
            )
          )
          publish()
        },
      )
      refreshPositions()
      refreshAccount()
    }

  override suspend fun refresh() =
    operation(MiningOperation.Refreshing) {
      chain.verify()
      // Signed bytes are durable before broadcast; retry the same bytes, never a new spend.
      runner.resumePending()
      refreshPositions()
      refreshAccount()
    }

  override suspend fun settle() =
    operation(MiningOperation.Recovering) {
      chain.verify()
      val address = requireWallet()
      val key = SolanaPublicKey.from(address)
      val miner = chain.client.miner(key) ?: error("This wallet has no mining position yet.")
      val round = miner.roundId
      check(round > 0uL)
      if (miner.checkpointId != round) {
        check(chain.result(round.toLong()) != OreRoundResult.Pending) {
          "The draw hasn't finished yet."
        }
        submit(
          "checkpoint:$address:$round",
          address,
          listOf(chain.program.checkpoint(key, key, round)) + OreMiningRpc.feeInstructions,
        )
      }
      var updated = checkNotNull(chain.client.miner(key))
      check(updated.checkpointId == round) {
        "Recovery is still confirming. Refresh before trying again."
      }
      if (updated.rewardsSol > 0uL)
        submit(
          "claim-sol:$address:$round:${updated.lastClaimSolAt}:${updated.rewardsSol}",
          address,
          listOf(chain.program.claimSol(key)) + OreMiningRpc.feeInstructions,
        )
      updated = checkNotNull(chain.client.miner(key))
      if (updated.rewardsOre > 0uL || updated.refinedOre > 0uL)
        submit(
          "claim-ore:$address:$round:${updated.lastClaimOreAt}:${updated.rewardsOre}:${updated.refinedOre}",
          address,
          listOf(chain.program.claimOre(key)) + OreMiningRpc.feeInstructions,
        )
      updated = checkNotNull(chain.client.miner(key))
      check(updated.rewardsSol == 0uL && updated.rewardsOre == 0uL && updated.refinedOre == 0uL) {
        "Some proceeds remain. Refresh before claiming again."
      }
      book.positions
        .filter {
          it.wallet == address &&
            it.round <= round.toLong() &&
            it.signature != null &&
            it.status != MiningStatus.Failed
        }
        .forEach { book.save(it.copy(status = MiningStatus.Settled, problem = null)) }
      publish()
      refreshAccount()
      mutable.value = mutable.value.copy(message = "Mining proceeds returned to your wallet.")
    }

  private suspend fun refreshPositions() {
    for (record in book.positions) {
      if (record.status in setOf(MiningStatus.Settled, MiningStatus.Failed)) continue
      val submission = journal.latest(deployOperation(record.reference)) ?: continue
      val position =
        record.copy(signature = submission.signature, validUntil = submission.validUntil)
      if (record.signature != submission.signature) book.save(position)
      val current = runner.reconcile(submission)
      val updated =
        when (current.state) {
          SubmissionState.FAILED,
          SubmissionState.EXPIRED ->
            position.copy(
              status = MiningStatus.Failed,
              problem = current.problem ?: "The mining transaction expired without confirmation.",
            )
          SubmissionState.PENDING -> position
          SubmissionState.CONFIRMED ->
            when (
              val proof =
                chain.verifyDeposit(position.deposit(), requireNotNull(position.signature))
            ) {
              DepositVerification.Pending -> position
              is DepositVerification.Rejected ->
                position.copy(status = MiningStatus.Failed, problem = proof.reason)
              DepositVerification.Confirmed ->
                when (val result = chain.result(position.round)) {
                  OreRoundResult.Pending ->
                    position.copy(status = MiningStatus.Mining, problem = null)
                  OreRoundResult.Unavailable ->
                    position.copy(
                      status = MiningStatus.Resolved,
                      problem =
                        "ORE has no published winning tile. Recover any available proceeds.",
                    )
                  is OreRoundResult.Resolved ->
                    position.copy(
                      status = MiningStatus.Resolved,
                      winningNumber = result.number,
                      problem = null,
                    )
                }
            }
        }
      if (position != updated) book.save(updated)
    }
    publish()
  }

  private suspend fun refreshAccount() {
    val address = mutable.value.wallet ?: return
    val key = SolanaPublicKey.from(address)
    val miner = chain.client.miner(key)
    val needsCheckpoint = miner != null && miner.roundId != miner.checkpointId
    val awaitingDraw =
      needsCheckpoint &&
        chain.result(requireNotNull(miner).roundId.toLong()) == OreRoundResult.Pending
    // Checkpoints and claims made directly in a wallet also finish our public receipt history.
    if (
      miner != null &&
        !needsCheckpoint &&
        miner.rewardsSol == 0uL &&
        miner.rewardsOre == 0uL &&
        miner.refinedOre == 0uL
    ) {
      book.positions
        .filter {
          it.wallet == address &&
            it.round <= miner.checkpointId.toLong() &&
            it.status in setOf(MiningStatus.Mining, MiningStatus.Resolved)
        }
        .forEach { book.save(it.copy(status = MiningStatus.Settled, problem = null)) }
    }
    mutable.value =
      mutable.value.copy(
        balance = chain.client.rpc.account(key)?.lamports ?: 0,
        claimableSol = miner?.rewardsSol ?: 0u,
        claimableOre = (miner?.rewardsOre ?: 0u) + (miner?.refinedOre ?: 0u),
        needsCheckpoint = needsCheckpoint,
        awaitingDraw = awaitingDraw,
        positions = book.positions,
      )
  }

  private suspend fun submit(
    operation: String,
    address: String,
    instructions: List<TransactionInstruction>,
    beforeSend: suspend () -> Unit = {},
    saved: (String, Long) -> Unit = { _, _ -> },
  ): String {
    journal
      .latest(operation)
      ?.takeIf { it.state in setOf(SubmissionState.PENDING, SubmissionState.CONFIRMED) }
      ?.let {
        saved(it.signature, it.validUntil)
        return runner.execute(it)
      }
    val blockhash = chain.client.rpc.latestBlockhashInfo()
    val unsigned =
      transaction(SolanaPublicKey.from(address), blockhash.value, *instructions.toTypedArray())
    val signed = wallet.sign(unsigned.serialize()).value()
    verifyWalletTransaction(
      signed,
      unsigned.message.serialize(),
      SolanaPublicKey.from(address).bytes,
    )
    check(chain.client.rpc.blockHeight() + 20 < blockhash.lastValidBlockHeight) {
      "Wallet approval took too long. No transaction was sent; try again."
    }
    beforeSend()
    val entry = journal.prepare(operation, signed, blockhash.lastValidBlockHeight, address)
    saved(entry.signature, entry.validUntil)
    return runner.execute(entry)
  }

  private fun requireWallet() = checkNotNull(mutable.value.wallet) { "Connect your wallet first." }

  private fun publish() {
    mutable.value = mutable.value.copy(positions = book.positions)
  }

  private suspend fun operation(kind: MiningOperation, action: suspend () -> Unit) {
    if (!mutex.tryLock()) return
    mutable.value =
      mutable.value.copy(
        operation = kind,
        message = if (kind == MiningOperation.Refreshing) mutable.value.message else null,
        refreshFailed = kind == MiningOperation.Refreshing && mutable.value.refreshFailed,
      )
    try {
      action()
      if (kind == MiningOperation.Refreshing && mutable.value.refreshFailed)
        mutable.value = mutable.value.copy(message = null, refreshFailed = false)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      mutable.value =
        mutable.value.copy(
          refreshFailed = kind == MiningOperation.Refreshing,
          message =
            when (kind) {
              MiningOperation.Connecting -> "Couldn't connect your wallet. Try again."
              MiningOperation.Refreshing ->
                "Couldn't update mining. Check your connection and retry."
              MiningOperation.Funding -> e.message ?: "Couldn't request test SOL. Try again."
              else ->
                if (e is IllegalStateException) e.message ?: "Couldn't complete mining. Try again."
                else "Couldn't confirm the transaction. Refresh before trying again."
            },
        )
    } finally {
      publish()
      mutable.value = mutable.value.copy(operation = null)
      mutex.unlock()
    }
  }

  private companion object {
    const val WALLET = "ore.devnet.wallet"
    const val AIRDROP_AT = "ore.devnet.airdrop-at"

    fun deployOperation(reference: String) = "deploy:$reference"
  }
}

private fun <T> WalletResult<T>.value(): T =
  when (this) {
    is WalletResult.Ok -> value
    is WalletResult.Failed -> error("Wallet approval wasn't completed. Try again.")
    WalletResult.NoWallet -> error("Connect a compatible Solana wallet and try again.")
  }

/** Wallets may reorder account keys while compiling the same instructions. No terms may change. */
internal fun verifyWalletTransaction(signed: ByteArray, message: ByteArray, wallet: ByteArray) {
  require(signed.size == 65 + message.size && signed[0] == 1.toByte()) {
    "Unexpected signed transaction"
  }
  val actual = Transaction.from(signed)
  val expected = Message.from(message)
  require(actual.message is LegacyMessage && expected is LegacyMessage)
  require(actual.serialize().contentEquals(signed)) { "Invalid signed transaction encoding" }
  fun Message.permissions(): Map<String, Boolean> {
    require(signatureCount == 1u.toUByte() && readOnlyAccounts == 0u.toUByte())
    require(readOnlyNonSigners.toInt() <= accounts.size - 1)
    require(accounts.distinct().size == accounts.size)
    require(accounts.first().bytes.contentEquals(wallet)) { "The wallet changed the fee payer" }
    return accounts
      .mapIndexed { index, key ->
        key.base58() to (index < accounts.size - readOnlyNonSigners.toInt())
      }
      .toMap()
  }
  val returned = actual.message
  require(
    returned.blockhash == expected.blockhash && returned.permissions() == expected.permissions()
  ) {
    "The wallet changed the blockhash or account permissions"
  }
  require(returned.instructions.size == expected.instructions.size) {
    "The wallet changed the instructions"
  }
  returned.instructions.zip(expected.instructions).forEach { (a, b) ->
    fun Message.key(index: Int) =
      requireNotNull(accounts.getOrNull(index)) { "Invalid account index" }
    require(
      returned.key(a.programIdIndex.toInt()) == expected.key(b.programIdIndex.toInt()) &&
        a.data.contentEquals(b.data) &&
        a.accountIndices.map { returned.key(it.toInt() and 255) } ==
          b.accountIndices.map { expected.key(it.toInt() and 255) }
    ) {
      "The wallet changed the transaction instructions"
    }
  }
  require(Ed25519.verify(actual.signatures.single(), returned.serialize(), wallet)) {
    "The selected wallet did not sign"
  }
}
