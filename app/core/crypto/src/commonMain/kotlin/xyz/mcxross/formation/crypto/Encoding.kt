package xyz.mcxross.formation.crypto

object Base58 {
  private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
  private val INDEX =
    IntArray(128) { -1 }.also { for ((i, c) in ALPHABET.withIndex()) it[c.code] = i }

  fun encode(input: ByteArray): String {
    if (input.isEmpty()) return ""
    val zeros = input.indexOfFirst { it != 0.toByte() }.let { if (it < 0) input.size else it }
    val number = input.copyOf()
    val out = CharArray(input.size * 2)
    var at = out.size
    var start = zeros
    while (start < number.size) {
      out[--at] = ALPHABET[divmod(number, start, 256, 58)]
      if (number[start] == 0.toByte()) start++
    }
    while (at < out.size && out[at] == ALPHABET[0]) at++
    repeat(zeros) { out[--at] = ALPHABET[0] }
    return out.concatToString(at, out.size)
  }

  fun decode(input: String): ByteArray {
    if (input.isEmpty()) return ByteArray(0)
    val digits =
      ByteArray(input.length) { i ->
        val c = input[i]
        val d = if (c.code < 128) INDEX[c.code] else -1
        require(d >= 0) { "'$c' is not a Base58 character" }
        d.toByte()
      }
    val zeros = digits.indexOfFirst { it != 0.toByte() }.let { if (it < 0) digits.size else it }
    val out = ByteArray(input.length)
    var at = out.size
    var start = zeros
    while (start < digits.size) {
      out[--at] = divmod(digits, start, 58, 256).toByte()
      if (digits[start] == 0.toByte()) start++
    }
    while (at < out.size && out[at] == 0.toByte()) at++
    return ByteArray(zeros) + out.copyOfRange(at, out.size)
  }

  /** Divides the big-endian [number] (in [base]) by [divisor] in place; returns the remainder. */
  private fun divmod(number: ByteArray, first: Int, base: Int, divisor: Int): Int {
    var remainder = 0
    for (i in first until number.size) {
      val temp = remainder * base + (number[i].toInt() and 0xff)
      number[i] = (temp / divisor).toByte()
      remainder = temp % divisor
    }
    return remainder
  }
}

private const val HEX = "0123456789abcdef"

fun ByteArray.toHex(): String =
  buildString(size * 2) {
    for (b in this@toHex) {
      append(HEX[(b.toInt() shr 4) and 0xf])
      append(HEX[b.toInt() and 0xf])
    }
  }

fun String.hexToBytes(): ByteArray {
  require(length % 2 == 0) { "Odd-length hex" }
  return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

object Base64 {
  fun encode(input: ByteArray): String =
    buildString((input.size + 2) / 3 * 4) {
      var i = 0
      while (i < input.size) {
        val b0 = input[i].toInt() and 0xff
        val b1 = if (i + 1 < input.size) input[i + 1].toInt() and 0xff else -1
        val b2 = if (i + 2 < input.size) input[i + 2].toInt() and 0xff else -1
        append(B64[b0 shr 2])
        append(B64[((b0 and 3) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
        append(if (b1 < 0) '=' else B64[((b1 and 0xf) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
        append(if (b2 < 0) '=' else B64[b2 and 0x3f])
        i += 3
      }
    }

  fun decode(input: String): ByteArray {
    val clean = input.filterNot { it.isWhitespace() }.trimEnd('=')
    val out = ByteArray(clean.length * 3 / 4)
    var buffer = 0
    var bits = 0
    var at = 0
    for (c in clean) {
      val v = B64.indexOf(c)
      require(v >= 0) { "'$c' is not a Base64 character" }
      buffer = (buffer shl 6) or v
      bits += 6
      if (bits >= 8) {
        bits -= 8
        out[at++] = (buffer shr bits).toByte()
      }
    }
    return out.copyOf(at)
  }
}
