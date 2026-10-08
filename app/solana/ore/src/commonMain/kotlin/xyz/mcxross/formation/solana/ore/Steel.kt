package xyz.mcxross.formation.solana.ore

import com.solana.publickey.SolanaPublicKey

internal fun ULong.littleEndian() = ByteArray(8) { (this shr (it * 8)).toByte() }

internal class SteelReader(private val data: ByteArray, discriminator: Int, size: Int) {
  private var offset = 8

  init {
    require(data.size == size) { "Expected $size account bytes, got ${data.size}" }
    require(data[0].toUByte().toInt() == discriminator) { "Unexpected ORE account discriminator" }
  }

  fun bytes(size: Int): ByteArray = data.copyOfRange(offset, offset + size).also { offset += size }

  fun key() = SolanaPublicKey(bytes(32))

  fun u64(): ULong =
    bytes(8).withIndex().fold(0uL) { result, (i, byte) ->
      result or (byte.toUByte().toULong() shl (i * 8))
    }

  fun i64(): Long = u64().toLong()

  fun squares(): List<ULong> = List(OreProgram.SQUARE_COUNT) { u64() }

  fun numeric() = OreNumeric(u64(), i64())
}

// Steel's Numeric stores a signed I80F48 value; retain both limbs without floating-point loss.
data class OreNumeric(val low: ULong, val high: Long)
