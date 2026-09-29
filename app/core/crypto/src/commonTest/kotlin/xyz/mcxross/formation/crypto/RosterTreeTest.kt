package xyz.mcxross.formation.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RosterTreeTest {
  private fun keys(n: Int) = List(n) { i -> RosterTree.Entry(ByteArray(32) { (i + 1).toByte() }) }

  private fun mixed(n: Int) =
    List(n) { i ->
      RosterTree.Entry(
        ByteArray(32) { (i + 1).toByte() },
        if (i % 2 == 0) ByteArray(32) { (0xA0 + i).toByte() } else null,
      )
    }

  // Computed independently with Python's hashlib; the vault program's tests use the same values.
  @Test
  fun rootsMatchTheReference() {
    assertEquals(
      "ecc024dbcf91045830cedc2e0ecd0315bc5bfd9d2ead1dbec20151e7c5d7ad9d",
      RosterTree(keys(1)).root.toHex(),
    )
    assertEquals(
      "fd9305db0088dde5775371b0d0af547afbaad864ad33c911dc59791a73ffbe5f",
      RosterTree(keys(2)).root.toHex(),
    )
    assertEquals(
      "0027d0372e39ec2ccbd8e699eb5e43558c1a5676d215289048576ad244a77bf3",
      RosterTree(keys(3)).root.toHex(),
    )
    assertEquals(
      "d4dda27d606f8199b30ddbb6168691a8ee42d5d63df0869a65c9c465417d4c27",
      RosterTree(keys(5)).root.toHex(),
    )
    assertEquals(
      "39125bb563ad6de5958fed28e25d91e70f6edd3572f51bed1066687bfb5dae24",
      RosterTree(mixed(3)).root.toHex(),
    )
    assertEquals(
      "c9c1d9296665cd67830af99a9a1e9dc256b633fe36961b1eaa03868caec53664",
      RosterTree(mixed(5)).root.toHex(),
    )
  }

  @Test
  fun everyHelperCanProveTheirPlace() {
    for (n in 1..17) {
      val entries = mixed(n)
      val tree = RosterTree(entries)
      entries.forEachIndexed { i, e ->
        assertTrue(
          RosterTree.verify(i, e.key, e.wallet, tree.proof(i), tree.root),
          "helper $i of $n",
        )
      }
    }
  }

  @Test
  fun proofsDoNotTransfer() {
    val entries = mixed(6)
    val tree = RosterTree(entries)
    assertFalse(RosterTree.verify(1, entries[2].key, null, tree.proof(1), tree.root))
    assertFalse(RosterTree.verify(2, entries[2].key, entries[2].wallet, tree.proof(1), tree.root))
    assertFalse(RosterTree.verify(0, entries[0].key, null, tree.proof(0), tree.root))
    assertFalse(RosterTree.verify(0, entries[0].key, ByteArray(32) { 1 }, tree.proof(0), tree.root))
  }
}
