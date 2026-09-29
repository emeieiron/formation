package xyz.mcxross.formation.solana

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.Transaction
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import xyz.mcxross.formation.crypto.Ed25519
import xyz.mcxross.formation.crypto.Ed25519KeyPair

class TransactionsTest {
  private val blockhash = "EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N"

  @Test
  fun theClaimKeySignsItsOwnSlot() = runTest {
    val claimKey = Ed25519KeyPair.fromSeed(ByteArray(32) { 5 })
    val wallet = SolanaPublicKey(ByteArray(32) { 1 })
    val mint = SolanaPublicKey(ByteArray(32) { 2 })
    val claimer = SolanaPublicKey(claimKey.publicKey)
    val ix = FormationVault().claim(wallet, claimer, wallet, mint, ByteArray(16), 0, emptyList())

    val signed = transaction(wallet, blockhash, ix).signedBy(claimKey)
    assertEquals(listOf(wallet, claimer), signed.message.accounts.take(2))
    assertContentEquals(ByteArray(64), signed.signatures[0])
    assertTrue(Ed25519.verify(signed.signatures[1], signed.message.serialize(), claimKey.publicKey))

    val decoded = Transaction.from(signed.serialize())
    assertContentEquals(signed.signatures[1], decoded.signatures[1])
    assertFailsWith<IllegalArgumentException> {
      transaction(wallet, blockhash, ix).signedBy(Ed25519KeyPair.fromSeed(ByteArray(32)))
    }
  }

  @Test
  fun explorerLinksNameTheCluster() {
    assertEquals("https://explorer.solana.com/tx/abc?cluster=devnet", explorerUrl("abc", "devnet"))
    assertEquals("https://explorer.solana.com/tx/abc", explorerUrl("abc", "mainnet-beta"))
    assertEquals(
      "https://explorer.solana.com/tx/abc?cluster=custom&customUrl=http%3A%2F%2Flocalhost%3A8899",
      explorerUrl("abc", "localnet"),
    )
  }
}
