package xyz.mcxross.formation.solana.ore

import com.solana.programs.ComputeBudgetProgram
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.TransactionInstruction
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.solana.SolanaRpc

/** A room's immutable mining terms. The reference is included in the signed transaction. */
data class OreDeposit(
  val reference: String,
  val wallet: String,
  val round: Long,
  val number: Int,
  val lamports: Long,
) {
  init {
    require(reference.length in 1..180 && reference.all { it.code in 33..126 })
    require(round > 0 && number in 1..25 && lamports > 0)
    SolanaPublicKey.from(wallet)
  }

  val memo: String
    get() = "formation:longshot:$reference:$round:$number:$lamports"
}

sealed interface DepositVerification {
  data object Pending : DepositVerification

  data object Confirmed : DepositVerification

  data class Rejected(val reason: String) : DepositVerification
}

interface OreMiningObserver {
  suspend fun fundingRound(): Long

  suspend fun verifyDeposit(
    deposit: OreDeposit,
    signature: String,
    validUntil: Long = Long.MAX_VALUE,
  ): DepositVerification

  suspend fun result(round: Long): OreRoundResult
}

/** Devnet transaction support; gameplay never receives a signer or administrative key. */
class OreMiningRpc(val client: OreRpc, private val finalized: SolanaRpc) : OreMiningObserver {
  val program
    get() = client.program

  private var verified = false

  suspend fun verify() {
    if (verified) return
    check(program.deployment.genesisHash == OreDeployment.Devnet.genesisHash) {
      "Longshot mining requires devnet"
    }
    client.verifyDeployment()
    check(finalized.genesisHash() == program.deployment.genesisHash) { "Unexpected result cluster" }
    verified = true
  }

  override suspend fun fundingRound(): Long {
    verify()
    val board = checkNotNull(client.board()) { "ORE board is unavailable" }
    val slot = client.rpc.slot().toULong()
    check(slot >= board.startSlot && (board.waitingForDeployment || board.endSlot > slot + 60uL)) {
      "Waiting for the next mining window"
    }
    check(board.roundId in 1uL..Long.MAX_VALUE.toULong())
    return board.roundId.toLong()
  }

  suspend fun checkDeposit(deposit: OreDeposit) {
    check(fundingRound() == deposit.round) { "The ORE round changed. Start a new turn." }
    val wallet = SolanaPublicKey.from(deposit.wallet)
    check(client.rpc.account(program.automation(wallet)) == null) {
      "Disable ORE automation on this wallet before playing Longshot."
    }
    val miner = client.miner(wallet)
    check(miner == null || miner.checkpointId == miner.roundId) {
      "Recover your previous mining proceeds before mining again."
    }
    check(miner == null || miner.roundId != deposit.round.toULong()) {
      "This wallet already mined this ORE round. Wait for the next round."
    }
    // Includes the first miner account's rent, checkpoint reserve, and transaction fees.
    check((client.rpc.account(wallet)?.lamports ?: 0) >= deposit.lamports + 10_000_000) {
      "You need at least 0.011 test SOL for mining, account rent, and fees."
    }
  }

  suspend fun depositInstructions(deposit: OreDeposit): List<TransactionInstruction> {
    val wallet = SolanaPublicKey.from(deposit.wallet)
    return listOf(
      program.deploy(
        wallet,
        wallet,
        deposit.round.toULong(),
        deposit.lamports.toULong(),
        setOf(deposit.number - 1),
      ),
      TransactionInstruction(MEMO, emptyList(), deposit.memo.encodeToByteArray()),
    ) + feeInstructions
  }

  override suspend fun verifyDeposit(
    deposit: OreDeposit,
    signature: String,
    validUntil: Long,
  ): DepositVerification {
    verify()
    if (runCatching { Base58.decode(signature).size }.getOrNull() != 64)
      return DepositVerification.Rejected("Invalid mining receipt")
    val receipt = finalized.transactionDetails(signature)
    if (receipt == null) {
      val status = client.rpc.signatureStatus(signature, history = true)
      if (status?.error != null)
        return DepositVerification.Rejected("The mining transaction failed.")
      if (status == null && client.rpc.blockHeight() > validUntil)
        return DepositVerification.Rejected("The mining transaction expired without landing.")
      return DepositVerification.Pending
    }
    val expected = depositInstructions(deposit)
    return verifyDepositReceipt(
      receipt,
      signature,
      deposit,
      expected,
      program.round(deposit.round.toULong()).base58(),
    )
  }

  override suspend fun result(round: Long): OreRoundResult {
    verify()
    require(round > 0)
    val accounts =
      finalized.multipleAccounts(listOf(program.board(), program.round(round.toULong())))
    fun owned(account: SolanaRpc.Account): ByteArray {
      check(account.owner == program.programId) { "Unexpected ORE account owner" }
      return account.data
    }
    val board = OreBoard.decode(owned(checkNotNull(accounts[0])))
    if (board.roundId <= round.toULong()) return OreRoundResult.Pending
    val account = accounts[1] ?: return OreRoundResult.Unavailable
    val resolved = OreRound.decode(owned(account))
    check(resolved.id == round.toULong()) { "Wrong ORE round" }
    return OreRoundReader.winningNumber(resolved.entropy)?.let(OreRoundResult::Resolved)
      ?: OreRoundResult.Unavailable
  }

  companion object {
    val MEMO = SolanaPublicKey.from("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr")

    // Set fees before signing so wallets need not append their own budget instructions.
    // Solflare applies this budget on devnet. Set it explicitly and reject further changes.
    // 400,000 units at 100,000 micro-lamports adds 40,000 lamports to the base fee.
    val feeInstructions: List<TransactionInstruction>
      get() =
        listOf(
          ComputeBudgetProgram.setComputeUnitLimit(400_000u),
          ComputeBudgetProgram.setComputeUnitPrice(100_000uL),
        )
  }
}

/** Check the actual deposit, not merely a successful transaction or a pre-existing position. */
internal fun verifyDepositReceipt(
  receipt: JsonObject,
  signature: String,
  deposit: OreDeposit,
  expected: List<TransactionInstruction>,
  roundAddress: String,
): DepositVerification =
  try {
    val meta = receipt.getValue("meta").jsonObject
    require(meta.getValue("err") == JsonNull) { "The mining transaction failed" }
    val tx = receipt.getValue("transaction").jsonObject
    require(
      tx.getValue("signatures").jsonArray.map { it.jsonPrimitive.content } == listOf(signature)
    )
    val message = tx.getValue("message").jsonObject
    val keys = message.getValue("accountKeys").jsonArray.map { it.jsonPrimitive.content }
    require(keys.first() == deposit.wallet)
    require(
      message.getValue("header").jsonObject.getValue("numRequiredSignatures").jsonPrimitive.int == 1
    )
    val instructions = message.getValue("instructions").jsonArray
    require(instructions.size == expected.size)
    instructions.zip(expected).forEach { (raw, ix) ->
      val actual = raw.jsonObject
      require(keys[actual.getValue("programIdIndex").jsonPrimitive.int] == ix.programId.base58())
      require(Base58.decode(actual.getValue("data").jsonPrimitive.content).contentEquals(ix.data))
      require(
        actual.getValue("accounts").jsonArray.map { keys[it.jsonPrimitive.int] } ==
          ix.accounts.map { it.publicKey.base58() }
      )
    }
    val index = keys.indexOf(roundAddress)
    require(index >= 0)
    val before = meta.getValue("preBalances").jsonArray[index].jsonPrimitive.long
    val after = meta.getValue("postBalances").jsonArray[index].jsonPrimitive.long
    require(after >= before && after - before == deposit.lamports) {
      "The transaction did not fund this position"
    }
    DepositVerification.Confirmed
  } catch (_: Exception) {
    DepositVerification.Rejected(
      "The receipt does not match this player's round, tile, amount, and room."
    )
  }
