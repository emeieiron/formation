package xyz.mcxross.formation.crypto

class Ed25519KeyPair(val seed: ByteArray, val publicKey: ByteArray) {
  init {
    require(seed.size == Ed25519.SEED_BYTES) { "An Ed25519 seed is 32 bytes" }
    require(publicKey.size == Ed25519.PUBLIC_KEY_BYTES) { "An Ed25519 public key is 32 bytes" }
  }

  fun sign(message: ByteArray): ByteArray = Ed25519.sign(message, this)

  companion object {
    fun generate(): Ed25519KeyPair = fromSeed(secureRandomBytes(Ed25519.SEED_BYTES))

    fun fromSeed(seed: ByteArray) = Ed25519KeyPair(seed.copyOf(), Ed25519.publicKey(seed))
  }
}

// TweetNaCl-derived (RFC 8032). Not constant-time: it signs seals and claims, never wallet funds.
object Ed25519 {
  const val SEED_BYTES = 32
  const val PUBLIC_KEY_BYTES = 32
  const val SIGNATURE_BYTES = 64

  fun publicKey(seed: ByteArray): ByteArray {
    require(seed.size == SEED_BYTES) { "An Ed25519 seed is 32 bytes" }
    return pack(scalarBase(expand(seed)))
  }

  fun sign(message: ByteArray, key: Ed25519KeyPair): ByteArray {
    val d = expand(key.seed)
    val r = Sha512.digest(d.copyOfRange(32, 64), message)
    reduce(r)
    val bigR = pack(scalarBase(r))
    val h = Sha512.digest(bigR, key.publicKey, message)
    reduce(h)
    val x = LongArray(64)
    for (i in 0 until 32) x[i] = u(r[i])
    for (i in 0 until 32) for (j in 0 until 32) x[i + j] += u(h[i]) * u(d[j])
    val s = ByteArray(32)
    modL(s, x)
    return bigR + s
  }

  fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
    if (signature.size != SIGNATURE_BYTES || publicKey.size != PUBLIC_KEY_BYTES) return false
    if (!isReducedScalar(signature, 32)) return false
    val negA = unpackNeg(publicKey) ?: return false
    val h = Sha512.digest(signature.copyOfRange(0, 32), publicKey, message)
    reduce(h)
    val p = point()
    scalarMult(p, negA, h)
    pointAdd(p, scalarBase(signature.copyOfRange(32, 64)))
    return constantTimeEquals(pack(p), signature.copyOfRange(0, 32))
  }

  /** SHA-512 of the seed with the scalar half clamped, as RFC 8032 section 5.1.5 prescribes. */
  private fun expand(seed: ByteArray): ByteArray =
    Sha512.digest(seed).also {
      it[0] = (it[0].toInt() and 248).toByte()
      it[31] = (it[31].toInt() and 127 or 64).toByte()
    }

  // --- GF(2^255 - 19) ---

  private val GF0 = LongArray(16)
  private val GF1 = LongArray(16).also { it[0] = 1 }

  private val D =
    longArrayOf(
      0x78a3,
      0x1359,
      0x4dca,
      0x75eb,
      0xd8ab,
      0x4141,
      0x0a4d,
      0x0070,
      0xe898,
      0x7779,
      0x4079,
      0x8cc7,
      0xfe73,
      0x2b6f,
      0x6cee,
      0x5203,
    )
  private val D2 =
    longArrayOf(
      0xf159,
      0x26b2,
      0x9b94,
      0xebd6,
      0xb156,
      0x8283,
      0x149a,
      0x00e0,
      0xd130,
      0xeef3,
      0x80f2,
      0x198e,
      0xfce7,
      0x56df,
      0xd9dc,
      0x2406,
    )
  private val X =
    longArrayOf(
      0xd51a,
      0x8f25,
      0x2d60,
      0xc956,
      0xa7b2,
      0x9525,
      0xc760,
      0x692c,
      0xdc5c,
      0xfdd6,
      0xe231,
      0xc0a4,
      0x53fe,
      0xcd6e,
      0x36d3,
      0x2169,
    )
  private val Y =
    longArrayOf(
      0x6658,
      0x6666,
      0x6666,
      0x6666,
      0x6666,
      0x6666,
      0x6666,
      0x6666,
      0x6666,
      0x6666,
      0x6666,
      0x6666,
      0x6666,
      0x6666,
      0x6666,
      0x6666,
    )
  private val SQRT_M1 =
    longArrayOf(
      0xa0b0,
      0x4a0e,
      0x1b27,
      0xc4ee,
      0xe478,
      0xad2f,
      0x1806,
      0x2f43,
      0xd7a7,
      0x3dfb,
      0x0099,
      0x2b4d,
      0xdf0b,
      0x4fc1,
      0x2480,
      0x2b83,
    )

  private fun carry(o: LongArray) {
    for (i in 0 until 16) {
      o[i] += 1L shl 16
      val c = o[i] shr 16
      if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
      o[i] -= c shl 16
    }
  }

  /** Swaps [p] and [q] when [b] is 1, without branching on it. */
  private fun select(p: LongArray, q: LongArray, b: Int) {
    val mask = (b - 1).toLong().inv()
    for (i in 0 until 16) {
      val t = mask and (p[i] xor q[i])
      p[i] = p[i] xor t
      q[i] = q[i] xor t
    }
  }

  private fun packField(o: ByteArray, n: LongArray) {
    val m = LongArray(16)
    val t = n.copyOf()
    carry(t)
    carry(t)
    carry(t)
    repeat(2) {
      m[0] = t[0] - 0xffed
      for (i in 1 until 15) {
        m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
        m[i - 1] = m[i - 1] and 0xffff
      }
      m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
      val b = ((m[15] shr 16) and 1).toInt()
      m[14] = m[14] and 0xffff
      select(t, m, 1 - b)
    }
    for (i in 0 until 16) {
      o[2 * i] = (t[i] and 0xff).toByte()
      o[2 * i + 1] = (t[i] shr 8).toByte()
    }
  }

  private fun differ(a: LongArray, b: LongArray): Boolean {
    val c = ByteArray(32)
    val d = ByteArray(32)
    packField(c, a)
    packField(d, b)
    return !constantTimeEquals(c, d)
  }

  private fun parity(a: LongArray): Int {
    val d = ByteArray(32)
    packField(d, a)
    return d[0].toInt() and 1
  }

  private fun unpackField(o: LongArray, n: ByteArray) {
    for (i in 0 until 16) o[i] = u(n[2 * i]) + (u(n[2 * i + 1]) shl 8)
    o[15] = o[15] and 0x7fff
  }

  private fun add(o: LongArray, a: LongArray, b: LongArray) {
    for (i in 0 until 16) o[i] = a[i] + b[i]
  }

  private fun sub(o: LongArray, a: LongArray, b: LongArray) {
    for (i in 0 until 16) o[i] = a[i] - b[i]
  }

  private fun mul(o: LongArray, a: LongArray, b: LongArray) {
    val t = LongArray(31)
    for (i in 0 until 16) for (j in 0 until 16) t[i + j] += a[i] * b[j]
    for (i in 0 until 15) t[i] += 38 * t[i + 16]
    for (i in 0 until 16) o[i] = t[i]
    carry(o)
    carry(o)
  }

  private fun square(o: LongArray, a: LongArray) = mul(o, a, a)

  private fun invert(o: LongArray, i: LongArray) {
    val c = i.copyOf()
    for (a in 253 downTo 0) {
      square(c, c)
      if (a != 2 && a != 4) mul(c, c, i)
    }
    c.copyInto(o)
  }

  private fun pow2523(o: LongArray, i: LongArray) {
    val c = i.copyOf()
    for (a in 250 downTo 0) {
      square(c, c)
      if (a != 1) mul(c, c, i)
    }
    c.copyInto(o)
  }

  // --- The curve, in extended coordinates (X, Y, Z, T) ---

  private fun point() = Array(4) { LongArray(16) }

  private fun pointAdd(p: Array<LongArray>, q: Array<LongArray>) {
    val a = LongArray(16)
    val b = LongArray(16)
    val c = LongArray(16)
    val d = LongArray(16)
    val t = LongArray(16)
    val e = LongArray(16)
    val f = LongArray(16)
    val g = LongArray(16)
    val h = LongArray(16)
    sub(a, p[1], p[0])
    sub(t, q[1], q[0])
    mul(a, a, t)
    add(b, p[0], p[1])
    add(t, q[0], q[1])
    mul(b, b, t)
    mul(c, p[3], q[3])
    mul(c, c, D2)
    mul(d, p[2], q[2])
    add(d, d, d)
    sub(e, b, a)
    sub(f, d, c)
    add(g, d, c)
    add(h, b, a)
    mul(p[0], e, f)
    mul(p[1], h, g)
    mul(p[2], g, f)
    mul(p[3], e, h)
  }

  private fun swap(p: Array<LongArray>, q: Array<LongArray>, b: Int) {
    for (i in 0 until 4) select(p[i], q[i], b)
  }

  private fun pack(p: Array<LongArray>): ByteArray {
    val zi = LongArray(16)
    val tx = LongArray(16)
    val ty = LongArray(16)
    invert(zi, p[2])
    mul(tx, p[0], zi)
    mul(ty, p[1], zi)
    val r = ByteArray(32)
    packField(r, ty)
    r[31] = (r[31].toInt() xor (parity(tx) shl 7)).toByte()
    return r
  }

  /** p = s·q. Clobbers [q]. */
  private fun scalarMult(p: Array<LongArray>, q: Array<LongArray>, s: ByteArray) {
    GF0.copyInto(p[0])
    GF1.copyInto(p[1])
    GF1.copyInto(p[2])
    GF0.copyInto(p[3])
    for (i in 255 downTo 0) {
      val b = ((u(s[i / 8]) shr (i and 7)) and 1).toInt()
      swap(p, q, b)
      pointAdd(q, p)
      pointAdd(p, p)
      swap(p, q, b)
    }
  }

  private fun scalarBase(s: ByteArray): Array<LongArray> {
    val q = point()
    X.copyInto(q[0])
    Y.copyInto(q[1])
    GF1.copyInto(q[2])
    mul(q[3], X, Y)
    val p = point()
    scalarMult(p, q, s)
    return p
  }

  /** Decodes a public key and negates it, or returns null if it is not on the curve. */
  private fun unpackNeg(key: ByteArray): Array<LongArray>? {
    val r = point()
    val t = LongArray(16)
    val chk = LongArray(16)
    val num = LongArray(16)
    val den = LongArray(16)
    val den2 = LongArray(16)
    val den4 = LongArray(16)
    val den6 = LongArray(16)
    GF1.copyInto(r[2])
    unpackField(r[1], key)
    square(num, r[1])
    mul(den, num, D)
    sub(num, num, r[2])
    add(den, r[2], den)
    square(den2, den)
    square(den4, den2)
    mul(den6, den4, den2)
    mul(t, den6, num)
    mul(t, t, den)
    pow2523(t, t)
    mul(t, t, num)
    mul(t, t, den)
    mul(t, t, den)
    mul(r[0], t, den)
    square(chk, r[0])
    mul(chk, chk, den)
    if (differ(chk, num)) mul(r[0], r[0], SQRT_M1)
    square(chk, r[0])
    mul(chk, chk, den)
    if (differ(chk, num)) return null
    if (parity(r[0]) == (u(key[31]) shr 7).toInt()) sub(r[0], GF0, r[0])
    mul(r[3], r[0], r[1])
    return r
  }

  // --- Scalars modulo the group order L ---

  private val L =
    longArrayOf(
      0xed,
      0xd3,
      0xf5,
      0x5c,
      0x1a,
      0x63,
      0x12,
      0x58,
      0xd6,
      0x9c,
      0xf7,
      0xa2,
      0xde,
      0xf9,
      0xde,
      0x14,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0x10,
    )

  private fun modL(r: ByteArray, x: LongArray) {
    for (i in 63 downTo 32) {
      var carry = 0L
      var j = i - 32
      while (j < i - 12) {
        x[j] += carry - 16 * x[i] * L[j - (i - 32)]
        carry = (x[j] + 128) shr 8
        x[j] -= carry shl 8
        j++
      }
      x[j] += carry
      x[i] = 0
    }
    var carry = 0L
    for (j in 0 until 32) {
      x[j] += carry - (x[31] shr 4) * L[j]
      carry = x[j] shr 8
      x[j] = x[j] and 255
    }
    for (j in 0 until 32) x[j] -= carry * L[j]
    for (i in 0 until 32) {
      x[i + 1] += x[i] shr 8
      r[i] = (x[i] and 255).toByte()
    }
  }

  /** Reduces a 64-byte little-endian number modulo L into its first 32 bytes. */
  private fun reduce(r: ByteArray) {
    val x = LongArray(64) { u(r[it]) }
    r.fill(0)
    modL(r, x)
  }

  /** Whether the 32 bytes at [offset] encode a scalar below L; rejects malleable signatures. */
  private fun isReducedScalar(bytes: ByteArray, offset: Int): Boolean {
    for (i in 31 downTo 0) {
      val b = u(bytes[offset + i])
      if (b < L[i]) return true
      if (b > L[i]) return false
    }
    return false
  }

  private fun u(b: Byte): Long = (b.toInt() and 0xff).toLong()
}

internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
  if (a.size != b.size) return false
  var diff = 0
  for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
  return diff == 0
}
