package xyz.mcxross.formation.crypto

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

class BouncyCastleCrossCheckTest {
  @Test
  fun keysAndSignaturesMatchBouncyCastle() {
    val random = Random(2026)
    repeat(300) {
      val seed = random.nextBytes(32)
      val message = random.nextBytes(random.nextInt(0, 300))
      val theirs = Ed25519PrivateKeyParameters(seed, 0)
      val ours = Ed25519KeyPair.fromSeed(seed)
      assertContentEquals(theirs.generatePublicKey().encoded, ours.publicKey)

      val signer = Ed25519Signer().apply { init(true, theirs) }
      signer.update(message, 0, message.size)
      val theirSignature = signer.generateSignature()
      assertContentEquals(theirSignature, ours.sign(message))

      assertTrue(Ed25519.verify(theirSignature, message, ours.publicKey))
      val verifier =
        Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(ours.publicKey, 0)) }
      verifier.update(message, 0, message.size)
      assertTrue(verifier.verifySignature(ours.sign(message)))
    }
  }
}
