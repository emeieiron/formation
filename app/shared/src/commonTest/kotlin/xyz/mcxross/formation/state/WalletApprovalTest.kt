package xyz.mcxross.formation.state

import com.solana.publickey.SolanaPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import xyz.mcxross.formation.solana.SolanaRpc
import xyz.mcxross.formation.solana.transaction

class WalletApprovalTest {
  private val payer = SolanaPublicKey(ByteArray(32) { 1 })

  // Each blockhash is valid until 150 blocks after the height it was fetched at; the wallet spends [approval] blocks.
  private class Chain(val approvals: MutableList<Long>) {
    var height = 1_000L
    val fetched = mutableListOf<String>()
    suspend fun latest() = SolanaRpc.Blockhash("hash${fetched.size}", height + 150).also { fetched += it.value }
    suspend fun height() = height
    suspend fun sign(txs: List<com.solana.transaction.Transaction>): List<ByteArray> {
      height += approvals.removeFirst()
      return txs.map { ByteArray(64) }
    }
  }

  private suspend fun approve(chain: Chain) =
    approvedInTime(chain::latest, chain::height, chain::sign) { hash ->
      hash to listOf(transaction(payer, "11111111111111111111111111111111"))
    }

  @Test
  fun aQuickApprovalIsUsedAsIs() = runTest {
    val chain = Chain(mutableListOf(40))
    val approved = approve(chain)
    assertEquals("hash0", approved.built)
    assertEquals(listOf("hash0"), chain.fetched)
  }

  @Test
  fun anApprovalThatOutlastsTheBlockhashIsAskedForAgainOnAFreshOne() = runTest {
    val chain = Chain(mutableListOf(140, 30))
    val approved = approve(chain)
    assertEquals("hash1", approved.built)
    assertEquals(1_000L + 140 + 150, approved.blockhash.lastValidBlockHeight)
  }

  @Test
  fun aWalletThatIsAlwaysTooSlowGetsAClearAnswer() = runTest {
    val chain = Chain(mutableListOf(200, 200, 200))
    val e = assertFailsWith<IllegalStateException> { approve(chain) }
    assertEquals("The wallet took too long to approve. Try again and approve right away.", e.message)
    assertEquals(3, chain.fetched.size)
  }
}
