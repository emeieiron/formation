package xyz.mcxross.formation.solana

import com.solana.publickey.SolanaPublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import xyz.mcxross.formation.crypto.hexToBytes

class FormationVaultTest {
  private val vault = FormationVault()
  private val id = "00112233445566778899aabbccddeeff".hexToBytes()
  private val wallet = SolanaPublicKey(ByteArray(32) { 1 })
  private val mint = SolanaPublicKey(ByteArray(32) { 2 })

  // Expected addresses come from `solana find-program-derived-address` and from mainnet.
  @Test
  fun addressesMatchTheReferences() = runTest {
    assertEquals("DRKaedTAauxGy8WTgDduizQ25YqqutTekHMw1baeB5nN", vault.config().base58())
    assertEquals("8pLyxQXZEouTmzEnBLrtxU3FbQDzcL9LUVoxxdP7kXiV", vault.opportunity(id).base58())
    assertEquals(
      Mainnet.sgtAccount,
      associatedTokenAccount(Mainnet.holder, Mainnet.sgt, SeekerGenesis.TOKEN_2022),
    )
    assertFailsWith<IllegalArgumentException> { vault.opportunity(ByteArray(15)) }
  }

  @Test
  fun titlesFitThirtyTwoBytes() {
    assertEquals(
      "Genesis Rally",
      FormationVault.titleOf(FormationVault.titleBytes("  Genesis Rally ")),
    )
    assertNull(FormationVault.titleOf(FormationVault.titleBytes(null)))
    assertNull(FormationVault.titleOf(FormationVault.titleBytes("   ")))
    val long = "The Long Wire, across the whole city"
    assertEquals(long.take(32).trim(), FormationVault.titleOf(FormationVault.titleBytes(long)))
    val stars = "✨".repeat(11)
    assertEquals("✨".repeat(10), FormationVault.titleOf(FormationVault.titleBytes(stars)))
    val rockets = "🚀".repeat(9)
    assertEquals("🚀".repeat(8), FormationVault.titleOf(FormationVault.titleBytes(rockets)))
  }

  @Test
  fun unlockAndClaimCarryTheRoster() = runTest {
    val root = ByteArray(32) { 7 }
    val result = ByteArray(32) { 8 }
    val unlock = vault.unlock(wallet, mint, id, root, 4, result)
    assertContentEquals(FormationVault.UNLOCK + root + byteArrayOf(4) + result, unlock.data)

    val proof = listOf(ByteArray(32) { 3 }, ByteArray(32) { 4 })
    val claim = vault.claim(wallet, mint, wallet, mint, id, 2, proof)
    assertContentEquals(
      FormationVault.CLAIM + byteArrayOf(2, 2, 0, 0, 0) + proof[0] + proof[1],
      claim.data,
    )
    assertFailsWith<IllegalArgumentException> {
      vault.claim(wallet, mint, wallet, mint, id, 0, List(8) { ByteArray(32) })
    }
  }

  @Test
  fun createLaysOutTheLock() = runTest {
    val lock = FormationVault.Lock(id, 600_000_000uL, 5, 5_000, 1, 2, "Rally", 1_800_000_000L)
    val data = vault.create(wallet, wallet, mint, mint, mint, lock).data
    assertEquals(8 + 16 + 8 + 1 + 2 + 2 + 1 + 32 + 8, data.size)
    assertContentEquals(id, data.copyOfRange(8, 24))
    assertEquals(600_000_000uL, data.u64At(24))
    assertEquals(5, data[32].toInt())
    assertEquals(5_000, data.u16At(33))
    assertEquals(1, data.u16At(35))
    assertEquals(2, data[37].toInt())
    assertEquals("Rally", FormationVault.titleOf(data.copyOfRange(38, 70)))
    assertEquals(1_800_000_000uL, data.u64At(70))
  }
}
