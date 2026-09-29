package xyz.mcxross.formation.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ed25519Test {
  private class Vector(
    val seed: String,
    val publicKey: String,
    val message: String,
    val signature: String,
  )

  // RFC 8032, section 7.1, tests 1 to 3.
  private val rfc8032 =
    listOf(
      Vector(
        "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
        "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
        "",
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
      ),
      Vector(
        "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
        "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
        "72",
        "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
      ),
      Vector(
        "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
        "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
        "af82",
        "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
      ),
    )

  @Test
  fun matchesRfc8032() {
    for (v in rfc8032) {
      val key = Ed25519KeyPair.fromSeed(v.seed.hexToBytes())
      assertEquals(v.publicKey, key.publicKey.toHex())
      val signature = key.sign(v.message.hexToBytes())
      assertEquals(v.signature, signature.toHex())
      assertTrue(Ed25519.verify(signature, v.message.hexToBytes(), key.publicKey))
    }
  }

  @Test
  fun rejectsTamperedMessagesSignaturesAndKeys() {
    val key = Ed25519KeyPair.generate()
    val other = Ed25519KeyPair.generate()
    val message = "formation".encodeToByteArray()
    val signature = key.sign(message)
    assertTrue(Ed25519.verify(signature, message, key.publicKey))
    assertFalse(Ed25519.verify(signature, "formatiom".encodeToByteArray(), key.publicKey))
    assertFalse(Ed25519.verify(signature, message, other.publicKey))
    for (i in listOf(0, 31, 32, 63)) {
      val bent = signature.copyOf().also { it[i] = (it[i].toInt() xor 0x04).toByte() }
      assertFalse(Ed25519.verify(bent, message, key.publicKey), "flipped a bit in byte $i")
    }
  }

  @Test
  fun rejectsNonCanonicalScalars() {
    val key = Ed25519KeyPair.generate()
    val message = byteArrayOf(1, 2, 3)
    val signature = key.sign(message)
    // S + L verifies on a lax implementation; a strict one must refuse it.
    val l = "edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010".hexToBytes()
    val s = signature.copyOfRange(32, 64)
    var carry = 0
    val sPlusL =
      ByteArray(32) { i ->
        val sum = (s[i].toInt() and 0xff) + (l[i].toInt() and 0xff) + carry
        carry = sum shr 8
        sum.toByte()
      }
    assertFalse(Ed25519.verify(signature.copyOfRange(0, 32) + sPlusL, message, key.publicKey))
  }

  @Test
  fun keysComeFromTheSeed() {
    val seed = ByteArray(32) { it.toByte() }
    assertContentEquals(Ed25519KeyPair.fromSeed(seed).publicKey, Ed25519.publicKey(seed))
    assertEquals(32, Ed25519KeyPair.generate().publicKey.size)
  }
}
