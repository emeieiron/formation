package xyz.mcxross.formation.solana

import xyz.mcxross.formation.crypto.Sha256

// The vault's keyed permutation of [0, n): entry i of a draw is selected when permute(i) < k.
object Selection {
  fun permute(seed: ByteArray, n: Long, index: Long): Long {
    if (n <= 1) return 0
    // A balanced Feistel network over the smallest even-bit domain holding n, cycle-walked back
    // into range.
    var bits = 32 - (n - 1).toInt().countLeadingZeroBits()
    bits += bits % 2
    val half = bits / 2
    val mask = (1L shl half) - 1
    var x = index
    while (true) {
      var left = x ushr half
      var right = x and mask
      for (round in 0 until 4) {
        val h =
          Sha256.digest(
            seed + byteArrayOf(round.toByte()) + BorshWriter().u64(right.toULong()).toByteArray()
          )
        val f = h.u64At(0).toLong() and mask
        val next = left xor f
        left = right
        right = next
      }
      x = (left shl half) or right
      if (x < n) return x
    }
  }
}
