package xyz.mcxross.formation.crypto

// leaf = sha256(0x00 ‖ index ‖ key ‖ wallet or 32 zero bytes), node = sha256(0x01 ‖ min(a, b) ‖
// max(a, b)) so proofs need no
// left/right bits, and an unpaired node moves up unchanged. The vault program hashes the same way.
class RosterTree(entries: List<Entry>) {
  class Entry(val key: ByteArray, val wallet: ByteArray? = null)

  init {
    require(entries.isNotEmpty()) { "A roster needs at least one helper" }
    require(entries.size <= MAX_SIZE) { "A roster holds at most $MAX_SIZE helpers" }
    require(entries.all { it.key.size == 32 && (it.wallet?.size ?: 32) == 32 }) {
      "Keys and wallets are 32 bytes"
    }
  }

  val size = entries.size

  private val levels: List<List<ByteArray>> = buildList {
    var level = entries.mapIndexed { i, e -> leaf(i, e.key, e.wallet) }
    add(level)
    while (level.size > 1) {
      level = level.chunked(2) { if (it.size == 2) node(it[0], it[1]) else it[0] }
      add(level)
    }
  }

  val root: ByteArray
    get() = levels.last().single().copyOf()

  fun proof(index: Int): List<ByteArray> {
    require(index in 0 until size) { "No helper at $index" }
    var at = index
    return buildList {
      for (level in levels.dropLast(1)) {
        val sibling = at xor 1
        if (sibling < level.size) add(level[sibling].copyOf())
        at /= 2
      }
    }
  }

  companion object {
    const val MAX_SIZE = 64
    private const val LEAF: Byte = 0
    private const val NODE: Byte = 1

    fun leaf(index: Int, key: ByteArray, wallet: ByteArray? = null): ByteArray =
      Sha256.digest(byteArrayOf(LEAF, index.toByte()), key, wallet ?: ByteArray(32))

    fun node(a: ByteArray, b: ByteArray): ByteArray =
      if (compareUnsigned(a, b) <= 0) Sha256.digest(byteArrayOf(NODE), a, b)
      else Sha256.digest(byteArrayOf(NODE), b, a)

    fun verify(
      index: Int,
      key: ByteArray,
      wallet: ByteArray?,
      proof: List<ByteArray>,
      root: ByteArray,
    ): Boolean = proof.fold(leaf(index, key, wallet), ::node).contentEquals(root)

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
      for (i in a.indices) {
        val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
        if (d != 0) return d
      }
      return 0
    }
  }
}
