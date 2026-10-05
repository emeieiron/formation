package xyz.mcxross.formation.solana

import kotlin.test.Test
import kotlin.test.assertEquals

class SelectionTest {
  // The vault's own tests pin the same values.
  @Test
  fun matchesTheVault() {
    assertEquals(listOf<Long>(5, 7, 9, 0, 6, 2, 1, 3, 8, 4), (0L until 10).map { Selection.permute(ByteArray(32) { 5 }, 10, it) })
    val seed = ByteArray(32) { it.toByte() }
    assertEquals(listOf<Long>(80, 34, 24, 3, 2), listOf(0L, 1, 2, 50, 99).map { Selection.permute(seed, 100, it) })
  }

  @Test
  fun isAPermutation() {
    for (n in listOf(1L, 2, 3, 17, 300)) {
      assertEquals((0 until n).toSet(), (0 until n).map { Selection.permute(ByteArray(32) { 9 }, n, it) }.toSet())
    }
  }
}
