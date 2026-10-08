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
    assertEquals("ADzKWRRvXEFbgRc7sPuqXwvj86oxeKc1aSsYWEhQ1ys4", vault.config().base58())
    val contest = vault.contest(wallet, 7uL)
    assertEquals("Dg87j4MzGxuLNrWDqq59buj8b74LpUFbVhbgmLuJuF6u", contest.base58())
    assertEquals(
      "769ySWS67xyk87yTJJ7gqHwg7jff49FxXxM6nx1DgkWz",
      vault.entry(contest, sgt, 1).base58(),
    )
    assertEquals(
      "FEa5s23KVReFboEcyKnWLS4W9YMVa4xNTuY9BoNJ6vDS",
      vault.receipt(contest, sgt).base58(),
    )
    assertEquals("2bsLH6hhVFFyQqAVyP2KysVtXMdQXcSFPXYVFthSHVCa", vault.testToken(wallet).base58())
    assertEquals("6AyLvcWZobvX7r4jv3p8QVUDZRnSqTHDWiscdggT5hMy", vault.testAuthority().base58())
    assertEquals(
      Mainnet.sgtAccount,
      associatedTokenAccount(Mainnet.holder, Mainnet.sgt, SeekerGenesis.TOKEN_2022),
    )
  }

  @Test
  fun titlesReadUpToTheFirstZero() {
    val bytes =
      ByteArray(FormationVault.TITLE_BYTES).also {
        "Genesis Rally ".encodeToByteArray().copyInto(it)
      }
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
    assertContentEquals(
      FormationVault.UNLOCK + byteArrayOf(2) + root + byteArrayOf(4) + result,
      unlock.data,
    )
    assertEquals(vault.entry(contest, sgt, 2), unlock.accounts[4].publicKey)
    val drawn = vault.unlockDrawn(holder, contest, mint, root, 4, result)
    assertContentEquals(FormationVault.UNLOCK_DRAWN + root + byteArrayOf(4) + result, drawn.data)
    assertEquals(vault.entry(contest, sgt, 0), drawn.accounts[4].publicKey)

    val proof = listOf(ByteArray(32) { 3 }, ByteArray(32) { 4 })
    val entry = vault.entry(contest, sgt, 0)
    val claim = vault.claim(wallet, mint, wallet, contest, entry, mint, 2, proof)
    assertContentEquals(
      FormationVault.CLAIM + byteArrayOf(2, 2, 0, 0, 0) + proof[0] + proof[1],
      claim.data,
    )
    assertEquals(vault.receipt(contest, mint), claim.accounts[5].publicKey)
    assertFailsWith<IllegalArgumentException> {
      vault.claim(wallet, mint, wallet, contest, entry, mint, 0, List(8) { ByteArray(32) })
    }
  }
}
