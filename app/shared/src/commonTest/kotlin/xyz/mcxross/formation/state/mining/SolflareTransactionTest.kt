package xyz.mcxross.formation.state.mining

import com.solana.transaction.Transaction
import com.solana.transaction.TransactionInstruction
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest
import xyz.mcxross.formation.solana.ore.OreDeposit
import xyz.mcxross.formation.solana.ore.OreMiningRpc
import xyz.mcxross.formation.solana.ore.OreProgram
import xyz.mcxross.formation.solana.transaction

class SolflareTransactionTest {
  @Test
  fun acceptsEquivalentTransactionReturnedByInstalledSolflare() = runTest {
    // Public devnet transaction returned by Solflare during the emulator test, never broadcast.
    val signed =
      Base64.decode(
        "ARxKkEAE9zhYT9Lt5fJ7OeAantHYj1Felg3O1zCx85+w/TbZiVow4HU9xkvZfHqWfOgCTZrSAO8iijAlKGuccgkBAAUN/SQBMuR0q1GzB7r0QBT62Gt//6e1tH4mh7jSN658FstNPCaSJ+4j5aDL2IGGvaXUy6vS7gIeVVGIZhf0xATtpGXbTx+gaq2kYFLp4SR6+ZIozkAFg7giZdFhOH+o9bqhig9cCDPng5HoJ5nlkK/srv+yGjIc82Qcf5FLFmUUciKSVjmqACWH+OT1lG1oBXy8H/KaNkI81Z8KGe/RTOMCgKD+WYAJcYHNTCrQeIOk7st8piUBbOejXZC0ESyhgk3vr/qVc6t1b5ojf5SX8yY1FuG/h1OnS9ijPp+M5hO72vTm2TRPF3tyopHeqbb+jtaL+PBUVEhxI61AtQ0ID4MHgwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAARAWp8iWdgb9OHBng1o7dm/M+g3ef8NnEZLhZEt1xpWdGhrZ4vUxbDm6TXIrgBTTfUcwC4L3nPbEbEHNti1O6eAMGRm/lIRcy/+ytunLDm+e8jOW7xfcSayxDmzpAAAAABUpTWpkpIQZNJOhxYNo4fHw1td28kruB5B+oQEEFRI1QZFtmkUh0606u0GnqsHVrg8Nns3rQ53008avY4nGx2QQJDAAABgMCBAcFCAkBCg0GQEIPAAAAAAAAAAEADABIZm9ybWF0aW9uOmxvbmdzaG90OjdhZjMyZjI2LTJjOGMtNDk4Yy1hMjM0LTMxYjIyNWYwMmMzYS8xLzM6NjoxNzoxMDAwMDAwCwAFAoAaBgALAAkDoIYBAAAAAAA="
      )
    val returned = Transaction.from(signed).message
    val wallet = returned.accounts.first()
    val deposit =
      OreDeposit("7af32f26-2c8c-498c-a234-31b225f02c3a/1/3", wallet.base58(), 6, 17, 1_000_000)
    val instructions =
      listOf(
        OreProgram().deploy(wallet, wallet, 6uL, 1_000_000uL, setOf(16)),
        TransactionInstruction(OreMiningRpc.MEMO, emptyList(), deposit.memo.encodeToByteArray()),
      ) + OreMiningRpc.feeInstructions
    val planned =
      transaction(wallet, returned.blockhash.base58(), *instructions.toTypedArray())
        .message
        .serialize()
    assertFalse(
      planned.contentEquals(returned.serialize()),
      "Solflare recompiles the account key order",
    )
    verifyWalletTransaction(signed, planned, wallet.bytes)
  }
}
