package xyz.mcxross.formation.solana

import com.solana.publickey.SolanaPublicKey

internal class BorshWriter {
  private val out = ArrayList<Byte>()

  fun bytes(b: ByteArray) = apply { b.forEach { out.add(it) } }

  fun fixed(b: ByteArray, size: Int) = apply {
    require(b.size == size) { "Expected $size bytes, got ${b.size}" }
    bytes(b)
  }

  fun key(k: SolanaPublicKey) = bytes(k.bytes)

  fun u8(v: Int) = apply {
    require(v in 0..0xff) { "$v doesn't fit a u8" }
    out.add(v.toByte())
  }

  fun u16(v: Int) = apply {
    require(v in 0..0xffff) { "$v doesn't fit a u16" }
    out.add(v.toByte())
    out.add((v shr 8).toByte())
  }

  fun u32(v: Int) = apply { repeat(4) { out.add((v shr (it * 8)).toByte()) } }

  fun u64(v: ULong) = apply { repeat(8) { out.add((v shr (it * 8)).toByte()) } }

  fun i64(v: Long) = u64(v.toULong())

  fun toByteArray() = out.toByteArray()
}

internal class BorshReader(private val data: ByteArray, discriminator: ByteArray) {
  private var at = discriminator.size

  init {
    require(
      data.size >= discriminator.size &&
        data.copyOfRange(0, discriminator.size).contentEquals(discriminator)
    ) {
      "Account data does not belong to this account type"
    }
  }

  fun bytes(n: Int): ByteArray {
    require(at + n <= data.size) { "Account data is truncated" }
    return data.copyOfRange(at, at + n).also { at += n }
  }

  fun key() = SolanaPublicKey(bytes(32))

  fun u8(): Int = bytes(1)[0].toInt() and 0xff

  fun u16(): Int = bytes(2).u16At(0)

  fun u32(): Long = bytes(4).let { b -> (0 until 4).fold(0L) { acc, i -> acc or ((b[i].toLong() and 0xff) shl (i * 8)) } }

  fun bool(): Boolean = u8() != 0

  fun u64(): ULong = bytes(8).u64At(0)

  fun i64(): Long = u64().toLong()
}

internal fun discriminator(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

internal fun ByteArray.u64At(offset: Int): ULong =
  (0 until 8).fold(0uL) { acc, i -> acc or (this[offset + i].toUByte().toULong() shl (i * 8)) }

internal fun ByteArray.u16At(offset: Int): Int =
  (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
