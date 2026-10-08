package xyz.mcxross.formation.crypto

class DerException(message: String) : Exception(message)

// The DER subset that X.509 certificates and key attestation records use. Certificates arrive from
// other phones, so every read is bounds-checked against its parent and indefinite lengths are
// refused.
internal class Der
private constructor(
  private val source: ByteArray,
  val tagClass: Int,
  val constructed: Boolean,
  val tag: Int,
  val start: Int,
  val contentStart: Int,
  val end: Int,
) {
  val encoded: ByteArray
    get() = source.copyOfRange(start, end)

  val content: ByteArray
    get() = source.copyOfRange(contentStart, end)

  fun isUniversal(tag: Int) = tagClass == UNIVERSAL && this.tag == tag

  fun isContext(tag: Int) = tagClass == CONTEXT && this.tag == tag

  fun children(): List<Der> {
    if (!constructed) throw DerException("Expected a constructed value")
    val out = mutableListOf<Der>()
    var at = contentStart
    while (at < end) {
      val child = read(source, at, end)
      out += child
      at = child.end
    }
    return out
  }

  fun sequence(): List<Der> = expect(SEQUENCE).children()

  fun set(): List<Der> = expect(SET).children()

  // EXPLICIT tagging wraps exactly one value.
  fun explicit(): Der = children().singleOrNull() ?: throw DerException("Expected one tagged value")

  fun octets(): ByteArray = expect(OCTET_STRING).content

  fun bitString(): ByteArray {
    val bytes = expect(BIT_STRING).content
    if (bytes.isEmpty() || bytes[0].toInt() != 0)
      throw DerException("Expected a whole-byte bit string")
    return bytes.copyOfRange(1, bytes.size)
  }

  fun integerBytes(): ByteArray {
    val bytes = expect(INTEGER).content
    if (bytes.isEmpty()) throw DerException("Empty integer")
    return bytes
  }

  fun long(): Long = signed(integerBytes())

  fun enumerated(): Long = signed(expect(ENUMERATED).content)

  fun boolean(): Boolean {
    val bytes = expect(BOOLEAN).content
    if (bytes.size != 1) throw DerException("Malformed boolean")
    return when (bytes[0].toInt() and 0xff) {
      0x00 -> false
      0xff -> true
      else -> throw DerException("Non-canonical boolean")
    }
  }

  fun oid(): String {
    val bytes = expect(OID).content
    if (bytes.isEmpty() || (bytes.last().toInt() and 0x80) != 0)
      throw DerException("Malformed object identifier")
    val parts = mutableListOf<Long>()
    var value = 0L
    var digits = 0
    for (byte in bytes) {
      val b = byte.toInt() and 0xff
      if (digits == 0 && b == 0x80) throw DerException("Non-minimal object identifier")
      value = (value shl 7) or (b and 0x7f).toLong()
      if (++digits > 9) throw DerException("Object identifier arc too large")
      if (b and 0x80 == 0) {
        parts += value
        value = 0
        digits = 0
      }
    }
    val first = parts.first()
    val head = if (first < 80) listOf(first / 40, first % 40) else listOf(2L, first - 80)
    return (head + parts.drop(1)).joinToString(".")
  }

  fun string(): String {
    if (tagClass != UNIVERSAL || tag !in TEXT_TAGS) throw DerException("Expected a string")
    return content.decodeToString()
  }

  fun time(): Long {
    val text = content.decodeToString()
    val full =
      when {
        isUniversal(UTC_TIME) && text.length == 13 -> {
          val year = text.substring(0, 2).toInt()
          (if (year >= 50) "19" else "20") + text
        }
        isUniversal(GENERALIZED_TIME) && text.length == 15 -> text
        else -> throw DerException("Unsupported time")
      }
    if (!full.endsWith("Z") || full.dropLast(1).any { it !in '0'..'9' })
      throw DerException("Malformed time")
    fun field(from: Int, length: Int) = full.substring(from, from + length).toInt()
    return epochMillis(
      field(0, 4),
      field(4, 2),
      field(6, 2),
      field(8, 2),
      field(10, 2),
      field(12, 2),
    )
  }

  private fun expect(tag: Int): Der {
    if (!isUniversal(tag))
      throw DerException("Expected tag $tag, found class $tagClass tag ${this.tag}")
    return this
  }

  companion object {
    const val UNIVERSAL = 0
    const val CONTEXT = 2
    const val BOOLEAN = 1
    const val INTEGER = 2
    const val BIT_STRING = 3
    const val OCTET_STRING = 4
    const val NULL = 5
    const val OID = 6
    const val ENUMERATED = 10
    const val SEQUENCE = 16
    const val SET = 17
    const val UTC_TIME = 23
    const val GENERALIZED_TIME = 24
    private val TEXT_TAGS = setOf(12, 19, 20, 22, 30)

    fun parse(bytes: ByteArray): Der {
      val value = read(bytes, 0, bytes.size)
      if (value.end != bytes.size) throw DerException("Trailing bytes after the value")
      return value
    }

    private fun read(source: ByteArray, offset: Int, limit: Int): Der {
      var at = offset
      fun next(): Int {
        if (at >= limit) throw DerException("Truncated value")
        return source[at++].toInt() and 0xff
      }
      val first = next()
      var tag = first and 0x1f
      if (tag == 0x1f) {
        tag = 0
        var digits = 0
        do {
          val b = next()
          if (digits == 0 && b == 0x80) throw DerException("Non-minimal tag")
          tag = (tag shl 7) or (b and 0x7f)
          if (++digits > 3) throw DerException("Tag number too large")
        } while (b and 0x80 != 0)
      }
      var length = next()
      if (length and 0x80 != 0) {
        val count = length and 0x7f
        if (count == 0 || count > 3) throw DerException("Unsupported length")
        length = 0
        repeat(count) { length = (length shl 8) or next() }
      }
      if (length > limit - at) throw DerException("Value runs past its container")
      return Der(source, first ushr 6, first and 0x20 != 0, tag, offset, at, at + length)
    }

    private fun signed(bytes: ByteArray): Long {
      if (bytes.isEmpty() || bytes.size > 8) throw DerException("Integer out of range")
      var value = if (bytes[0] < 0) -1L else 0L
      for (b in bytes) value = (value shl 8) or (b.toLong() and 0xff)
      return value
    }
  }
}

// Index and number slips inside a malformed structure surface as DerException, never as a crash.
internal inline fun <T> malformedAs(what: String, read: () -> T): T =
  try {
    read()
  } catch (e: DerException) {
    throw e
  } catch (e: RuntimeException) {
    throw DerException("Malformed $what")
  }

// Days from the civil calendar, after Howard Hinnant's algorithm, so certificate times need no date
// library.
internal fun epochMillis(
  year: Int,
  month: Int,
  day: Int,
  hour: Int,
  minute: Int,
  second: Int,
): Long {
  if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60)
    throw DerException("Malformed time")
  val y = (if (month <= 2) year - 1 else year).toLong()
  val era = (if (y >= 0) y else y - 399) / 400
  val yearOfEra = y - era * 400
  val dayOfYear = (153 * ((month + 9) % 12) + 2) / 5 + day - 1
  val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
  val days = era * 146_097 + dayOfEra - 719_468
  return (((days * 24 + hour) * 60 + minute) * 60 + second) * 1_000
}
