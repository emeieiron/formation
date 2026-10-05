package xyz.mcxross.formation.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DerTest {
  private fun der(hex: String) = Der.parse(hex.hexToBytes())

  @Test
  fun readsScalars() {
    assertEquals(-129L, der("0202ff7f").long())
    assertEquals(400L, der("02020190").long())
    assertEquals(true, der("0101ff").boolean())
    assertEquals("1.2.840.113549.1.1.11", der("06092a864886f70d01010b").oid())
    assertEquals(0L, der("170d3730303130313030303030305a").time())
    assertEquals(2_461_449_600_000L, der("180f32303438303130313030303030305a").time())
  }

  @Test
  fun readsHighTagNumbers() {
    // [704] EXPLICIT INTEGER 7, as an authorization list writes the root-of-trust tag.
    val tagged = der("bf8540030201" + "07")
    assertEquals(Der.CONTEXT, tagged.tagClass)
    assertEquals(704, tagged.tag)
    assertEquals(7L, tagged.explicit().long())
  }

  @Test
  fun refusesMalformedEncodings() {
    assertFailsWith<DerException> { der("0101 01".replace(" ", "")).boolean() }
    assertFailsWith<DerException> { der("3080") }
    assertFailsWith<DerException> { der("3005020101") }
    assertFailsWith<DerException> { der("020101ff") }
    assertFailsWith<DerException> { der("1f8080") }
    assertFailsWith<DerException> { der("170d3730313330313030303030305a").time() }
  }
}
