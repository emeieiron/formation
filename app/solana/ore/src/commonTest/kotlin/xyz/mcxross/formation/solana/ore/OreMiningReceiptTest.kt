package xyz.mcxross.formation.solana.ore

import com.solana.programs.ComputeBudgetProgram
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.TransactionInstruction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import xyz.mcxross.formation.crypto.Base58

class OreMiningReceiptTest {
  private val program = OreProgram()
  private val wallet = SolanaPublicKey.from("11111111111111111111111111111111")
  private val deposit = OreDeposit("room/1/2", wallet.base58(), 6, 17, 1_000_000)
  private val signature = Base58.encode(ByteArray(64) { 3 })

  private suspend fun instructions(terms: OreDeposit) =
    listOf(
      program.deploy(
        wallet,
        wallet,
        terms.round.toULong(),
        terms.lamports.toULong(),
        setOf(terms.number - 1),
      ),
      TransactionInstruction(OreMiningRpc.MEMO, emptyList(), terms.memo.encodeToByteArray()),
    ) + OreMiningRpc.feeInstructions

  private suspend fun receipt(
    terms: OreDeposit = deposit,
    deposited: Long = terms.lamports,
    failed: Boolean = false,
    extraInstruction: Boolean = false,
    signer: String = wallet.base58(),
    overrideInstructions: List<TransactionInstruction>? = null,
  ): JsonObject {
    val instructions =
      (overrideInstructions ?: instructions(terms)).let {
        if (extraInstruction) it + it.last() else it
      }
    val keys =
      (listOf(signer) +
          instructions.flatMap {
            it.accounts.map { it.publicKey.base58() } + it.programId.base58()
          })
        .distinct()
    val round = program.round(terms.round.toULong()).base58()
    return buildJsonObject {
      putJsonObject("meta") {
        put("err", if (failed) buildJsonObject { put("InstructionError", 0) } else JsonNull)
        putJsonArray("preBalances") { keys.forEach { add(10_000_000L) } }
        putJsonArray("postBalances") {
          keys.forEach { add(10_000_000L + if (it == round) deposited else 0L) }
        }
      }
      putJsonObject("transaction") {
        putJsonArray("signatures") { add(signature) }
        putJsonObject("message") {
          putJsonArray("accountKeys") { keys.forEach { add(it) } }
          putJsonObject("header") { put("numRequiredSignatures", 1) }
          putJsonArray("instructions") {
            instructions.forEach { ix ->
              add(
                buildJsonObject {
                  put("programIdIndex", keys.indexOf(ix.programId.base58()))
                  put("data", Base58.encode(ix.data))
                  putJsonArray("accounts") {
                    ix.accounts.forEach { add(keys.indexOf(it.publicKey.base58())) }
                  }
                }
              )
            }
          }
        }
      }
    }
  }

  private suspend fun verify(receipt: JsonObject, terms: OreDeposit = deposit) =
    verifyDepositReceipt(
      receipt,
      signature,
      terms,
      instructions(terms),
      program.round(terms.round.toULong()).base58(),
    )

  @Test
  fun acceptsAnExactSingleTileDeposit() = runTest {
    assertEquals(DepositVerification.Confirmed, verify(receipt()))
  }

  @Test
  fun rejectsAReplayAcrossRoomTurnRoundTileAndAmount() = runTest {
    val receipt = receipt()
    listOf(
        deposit.copy(reference = "other/1/2"),
        deposit.copy(reference = "room/1/3"),
        deposit.copy(round = 7),
        deposit.copy(number = 18),
        deposit.copy(lamports = 2_000_000),
      )
      .forEach { assertIs<DepositVerification.Rejected>(verify(receipt, it)) }
  }

  @Test
  fun aSuccessfulNoOpOrPartialFundingIsNotAMiningReceipt() = runTest {
    assertIs<DepositVerification.Rejected>(verify(receipt(deposited = 0)))
    assertIs<DepositVerification.Rejected>(verify(receipt(deposited = 999_999)))
    assertIs<DepositVerification.Rejected>(verify(receipt(deposited = 2_000_000)))
  }

  @Test
  fun rejectsFailureExtraInstructionsAndAnotherFeePayer() = runTest {
    assertIs<DepositVerification.Rejected>(verify(receipt(failed = true)))
    assertIs<DepositVerification.Rejected>(verify(receipt(extraInstruction = true)))
    assertIs<DepositVerification.Rejected>(verify(receipt(signer = program.programId.base58())))
    assertIs<DepositVerification.Rejected>(verify(buildJsonObject {}))
  }

  @Test
  fun rejectsWalletAppendedOrChangedPriorityFees() = runTest {
    val planned = instructions(deposit)
    assertIs<DepositVerification.Rejected>(
      verify(
        receipt(
          overrideInstructions =
            planned.dropLast(1) + ComputeBudgetProgram.setComputeUnitPrice(200_000uL)
        )
      )
    )
    assertIs<DepositVerification.Rejected>(
      verify(receipt(overrideInstructions = planned.dropLast(2)))
    )
  }
}
