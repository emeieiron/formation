package xyz.mcxross.formation.state.mining

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.LegacyMessage
import com.solana.transaction.Transaction
import com.solana.transaction.TransactionInstruction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.solana.ore.OreMiningRpc
import xyz.mcxross.formation.solana.transaction

class MiningSafetyTest {
  private val key = Ed25519KeyPair.fromSeed(ByteArray(32) { 1 })
  private val message =
    transaction(
        SolanaPublicKey(key.publicKey),
        Base58.encode(ByteArray(32) { 9 }),
        TransactionInstruction(
          SolanaPublicKey(ByteArray(32) { 7 }),
          listOf(AccountMeta(SolanaPublicKey(ByteArray(32) { 3 }), false, true)),
          byteArrayOf(1, 2, 3),
        ),
        *OreMiningRpc.feeInstructions.toTypedArray(),
      )
      .message as LegacyMessage

  private fun signed(message: LegacyMessage, signer: Ed25519KeyPair = key) =
    Transaction(listOf(signer.sign(message.serialize())), message).serialize()

  private class Store : KeyValueStore {
    val values = mutableMapOf<String, String>()
    var failWrites = false

    override fun get(key: String) = values[key]

    override fun put(key: String, value: String?) {
      check(!failWrites) { "Disk full" }
      if (value == null) values.remove(key) else values[key] = value
    }
  }

  @Test
  fun walletCannotChangeTheMessageOrSignWithAnotherAccount() {
    val other = Ed25519KeyPair.fromSeed(ByteArray(32) { 2 })
    val signed = signed(message)
    verifyWalletTransaction(signed, message.serialize(), key.publicKey)
    assertFailsWith<IllegalArgumentException> {
      verifyWalletTransaction(signed(message, other), message.serialize(), key.publicKey)
    }
    assertFailsWith<IllegalArgumentException> {
      verifyWalletTransaction(
        signed(message.copy(blockhash = SolanaPublicKey(ByteArray(32) { 8 }))),
        message.serialize(),
        key.publicKey,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      verifyWalletTransaction(
        signed.copyOf().also { it[2] = 0 },
        message.serialize(),
        key.publicKey,
      )
    }
  }

  @Test
  fun equivalentAccountOrderingIsAcceptedButInstructionsPermissionsAndFeesCannotChange() {
    val reordered =
      message.accounts.toMutableList().apply {
        val last = lastIndex
        val swap = this[last]
        this[last] = this[last - 1]
        this[last - 1] = swap
      }
    fun index(old: Int) = reordered.indexOf(message.accounts[old])
    val equivalent =
      message.copy(
        accounts = reordered,
        instructions =
          message.instructions.map {
            it.copy(
              programIdIndex = index(it.programIdIndex.toInt()).toUByte(),
              accountIndices =
                it.accountIndices.map { index(it.toInt() and 255).toByte() }.toByteArray(),
            )
          },
      )
    verifyWalletTransaction(signed(equivalent), message.serialize(), key.publicKey)
    val changedData =
      message.instructions.toMutableList().apply {
        this[lastIndex] = last().copy(data = last().data.copyOf().also { it[1] = 1 })
      }
    listOf(
        message.copy(accounts = reordered),
        message.copy(instructions = message.instructions.reversed()),
        message.copy(readOnlyNonSigners = (message.readOnlyNonSigners - 1u).toUByte()),
        message.copy(instructions = changedData),
        message.copy(instructions = message.instructions + message.instructions.last()),
      )
      .forEach { changed ->
        assertFailsWith<IllegalArgumentException> {
          verifyWalletTransaction(signed(changed), message.serialize(), key.publicKey)
        }
      }
  }

  @Test
  fun receiptsSurviveRestartAndFailedPersistenceDoesNotPublishThem() {
    val disk = Store()
    val store = MiningStore(disk)
    val prepared = MiningPosition("room/1/1", "11111111111111111111111111111111", 6, 17, 1_000_000)
    store.save(prepared)
    val submitted =
      prepared.copy(signature = "signature", validUntil = 90, status = MiningStatus.Submitted)
    disk.failWrites = true
    assertFailsWith<IllegalStateException> { store.save(submitted) }
    assertEquals(listOf(prepared), store.positions)
    disk.failWrites = false
    store.save(submitted)
    assertEquals(listOf(submitted), MiningStore(disk).positions)
  }
}
