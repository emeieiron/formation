package xyz.mcxross.formation.crypto

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class EncodingTest {
  @Test
  fun base58KnownValues() {
    assertEquals("2NEpo7TZRRrLZSi2U", Base58.encode("Hello World!".encodeToByteArray()))
    assertEquals("11111111111111111111111111111111", Base58.encode(ByteArray(32)))
    assertEquals("1112", Base58.encode(byteArrayOf(0, 0, 0, 1)))
    // The System Program's address is 32 zero bytes.
    assertContentEquals(ByteArray(32), Base58.decode("11111111111111111111111111111111"))
  }

  @Test
  fun base58RoundTrips() {
    val random = Random(7)
    repeat(200) {
      val bytes = random.nextBytes(random.nextInt(0, 70))
      if (bytes.isNotEmpty() && random.nextBoolean()) bytes[0] = 0
      assertContentEquals(bytes, Base58.decode(Base58.encode(bytes)))
    }
  }

  @Test
  fun base64RoundTrips() {
    assertEquals("Zm9ybWF0aW9u", Base64.encode("formation".encodeToByteArray()))
    assertEquals("Zg==", Base64.encode("f".encodeToByteArray()))
    val random = Random(11)
    repeat(200) {
      val bytes = random.nextBytes(random.nextInt(0, 90))
      assertContentEquals(bytes, Base64.decode(Base64.encode(bytes)))
    }
  }

  @Test
  fun hexRoundTrips() {
    val bytes = byteArrayOf(0, 15, 16, -1, 127, -128)
    assertEquals("000f10ff7f80", bytes.toHex())
    assertContentEquals(bytes, "000f10ff7f80".hexToBytes())
  }
}
