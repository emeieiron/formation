package xyz.mcxross.formation.solana

import com.solana.publickey.SolanaPublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class FormationVaultTest {
  private val vault = FormationVault()
  private val wallet = SolanaPublicKey(ByteArray(32) { 1 })
  private val sgt = SolanaPublicKey(ByteArray(32) { 2 })
  private val mint = SolanaPublicKey(ByteArray(32) { 3 })

  // Expected addresses come from solders' find_program_address and from mainnet.
  @Test
  fun addressesMatchTheReferences() = runTest {
    assertEquals("GowL9T7H2YekJFW1869bF3KoXA3bdzugpbGH3pJVGRN4", vault.config().base58())
    val contest = vault.contest(wallet, 7uL)
    assertEquals("DXNnGZzzKap9FqfyTVnY3QevoJMMQuGY3ahAfNPiN5ww", contest.base58())
    assertEquals("nVCStjGbx7tnKP41jMjdCkTovaBcA5uc1LsGWd6YnTR", vault.entry(contest, sgt, 1).base58())
    assertEquals("D2FtRzJSpzeANUBb5JQFc5MXzAWNvo2E4ZUokeHAU8a9", vault.receipt(contest, sgt).base58())
    assertEquals(
      Mainnet.sgtAccount,
      associatedTokenAccount(Mainnet.holder, Mainnet.sgt, SeekerGenesis.TOKEN_2022),
    )
  }

  @Test
  fun titlesReadUpToTheFirstZero() {
    val bytes = ByteArray(FormationVault.TITLE_BYTES).also { "Genesis Rally ".encodeToByteArray().copyInto(it) }
    assertEquals("Genesis Rally", FormationVault.titleOf(bytes))
    assertEquals(null, FormationVault.titleOf(ByteArray(FormationVault.TITLE_BYTES)))
  }

  @Test
  fun unlockAndClaimCarryTheRoster() = runTest {
    val holder = FormationVault.Holder(wallet, sgt, mint)
    val contest = vault.contest(wallet, 1uL)
    val root = ByteArray(32) { 7 }
    val result = ByteArray(32) { 8 }
    val unlock = vault.unlock(holder, contest, mint, 2, root, 4, result)
    assertContentEquals(FormationVault.UNLOCK + byteArrayOf(2) + root + byteArrayOf(4) + result, unlock.data)
    assertEquals(vault.entry(contest, sgt, 2), unlock.accounts[4].publicKey)
    val drawn = vault.unlockDrawn(holder, contest, mint, root, 4, result)
    assertContentEquals(FormationVault.UNLOCK_DRAWN + root + byteArrayOf(4) + result, drawn.data)
    assertEquals(vault.entry(contest, sgt, 0), drawn.accounts[4].publicKey)

    val proof = listOf(ByteArray(32) { 3 }, ByteArray(32) { 4 })
    val entry = vault.entry(contest, sgt, 0)
    val claim = vault.claim(wallet, mint, wallet, contest, entry, mint, 2, proof)
    assertContentEquals(FormationVault.CLAIM + byteArrayOf(2, 2, 0, 0, 0) + proof[0] + proof[1], claim.data)
    assertEquals(vault.receipt(contest, mint), claim.accounts[5].publicKey)
    assertFailsWith<IllegalArgumentException> {
      vault.claim(wallet, mint, wallet, contest, entry, mint, 0, List(8) { ByteArray(32) })
    }
  }
}
