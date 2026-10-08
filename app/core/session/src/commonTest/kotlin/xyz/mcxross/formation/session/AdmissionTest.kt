package xyz.mcxross.formation.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Ed25519KeyPair

class AdmissionTest {
  private val key = Ed25519KeyPair.generate()
  private val challenge =
    AdmissionChallenge("session", "fresh-nonce", "tap", 1, setOf("ACCELEROMETER"))

  private fun signed(
    challenge: AdmissionChallenge = this.challenge,
    hello: ToHost.Hello =
      ToHost.Hello(
        PROTOCOL_VERSION,
        "device",
        "Player",
        0,
        Base58.encode(key.publicKey),
        formats = mapOf("tap" to 1),
        capabilities = setOf("ACCELEROMETER"),
        nonce = challenge.nonce,
      ),
  ) = hello.copy(signature = Base58.encode(key.sign(admissionMessage(challenge, hello))))

  @Test
  fun aSignatureCannotBeReplayedOnAnotherConnectionOrAlterItsWallet() {
    val hello = signed()
    assertNull(admissionRejection(challenge, hello))
    assertEquals(
      Rejection.IDENTITY,
      admissionRejection(challenge.copy(nonce = "next-nonce"), hello),
    )
    assertEquals(
      Rejection.IDENTITY,
      admissionRejection(challenge.copy(session = "other-session"), hello),
    )
    assertEquals(
      Rejection.IDENTITY,
      admissionRejection(challenge, hello.copy(wallet = Base58.encode(ByteArray(32) { 3 }))),
    )
    assertEquals(
      Rejection.IDENTITY,
      admissionRejection(
        challenge,
        hello.copy(claimKey = Base58.encode(Ed25519KeyPair.generate().publicKey)),
      ),
    )
  }

  @Test
  fun formatsAndCapabilitiesAreCheckedBeforeAssigningASeat() {
    val hello = signed()
    assertEquals(
      Rejection.FORMAT,
      admissionRejection(challenge, signed(hello = hello.copy(formats = mapOf("tap" to 2)))),
    )
    assertEquals(
      Rejection.CAPABILITY,
      admissionRejection(challenge, signed(hello = hello.copy(capabilities = emptySet()))),
    )
    assertEquals(
      Rejection.VERSION,
      admissionRejection(challenge, signed(hello = hello.copy(protocol = PROTOCOL_VERSION - 1))),
    )
  }
}
