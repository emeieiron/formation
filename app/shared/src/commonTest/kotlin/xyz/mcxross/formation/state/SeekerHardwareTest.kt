package xyz.mcxross.formation.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.platform.DeviceAttestation
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.session.SeekerProof

class SeekerHardwareTest {
  private class Store : KeyValueStore {
    val map = mutableMapOf<String, String>()

    override fun get(key: String) = map[key]

    override fun put(key: String, value: String?) {
      if (value == null) map.remove(key) else map[key] = value
    }
  }

  private class Phone(val chain: () -> List<ByteArray>) : DeviceAttestation {
    override val packageName = "xyz.mcxross.formation"
    override val signers = setOf("5e0a")

    override suspend fun attest(challenge: ByteArray) = chain()
  }

  private var clock = 1_000L
  private var requests = 0
  private var online = true

  private fun revocations(store: KeyValueStore = Store()) = AttestationRevocations(store, { clock }) {
    requests++
    """{"entries":{"C35747A0":{"status":"REVOKED"},"8350192447815228107":{"status":"SUSPENDED"}}}""".takeIf { online }
  }

  private fun hardware(phone: DeviceAttestation, debug: Boolean = false) = SeekerHardware(phone, revocations(), debug, { clock })

  @Test
  fun revocationsAreFetchedOnceADayAndKeptOffline() = runTest {
    val store = Store()
    val list = revocations(store)
    assertEquals(setOf("c35747a0", "8350192447815228107"), list.current())
    assertEquals(setOf("c35747a0", "8350192447815228107"), list.current())
    assertEquals(1, requests)

    online = false
    clock += 2 * 24 * 60 * 60 * 1_000L
    assertEquals(setOf("c35747a0", "8350192447815228107"), revocations(store).current(), "a stale list still beats none")
    assertEquals(2, requests)
  }

  @Test
  fun aPhoneThatNeverFetchedTheListHasNone() = runTest {
    online = false
    assertNull(revocations().current())
    assertNull(AttestationRevocations.parse("not json"))
  }

  @Test
  fun phonesThatCantAttestCantHost() = runTest {
    val hardware = hardware(Phone { throw IllegalStateException("This phone can't attest its model.") })
    val error = assertFailsWith<IllegalStateException> { hardware.host("session-1", simulated = false) }
    assertEquals("Only a Seeker can host. This phone can't attest its model.", error.message)
    assertEquals(HardwareCheck.Failed("This phone can't attest its model."), hardware.local.value)
  }

  @Test
  fun aChainThatIsntGooglesIsRefused() = runTest {
    val hardware = hardware(Phone { listOf(byteArrayOf(0x30, 0x00), byteArrayOf(0x30, 0x00)) })
    assertEquals(false, hardware.proveThisPhone())
    val failed = assertIs<HardwareCheck.Failed>(hardware.local.value)
    assertTrue("can't be read" in failed.message, failed.message)
  }

  @Test
  fun onlyDebugBuildsPretend() = runTest {
    val phone = Phone { error("never asked") }
    val proof = hardware(phone, debug = true).host("session-1", simulated = true).proof
    assertEquals(true, proof.simulated)
    assertEquals(32, Base58.decode(proof.sessionKey).size)
    assertFailsWith<IllegalStateException> { hardware(phone).host("session-1", simulated = true) }

    val key = Base58.encode(Ed25519KeyPair.generate().publicKey)
    assertNull(hardware(phone, debug = true).verifier.problem("session-1", SeekerProof(key, simulated = true)))
    assertEquals("This host is a test Seeker from a developer build.",
      hardware(phone).verifier.problem("session-1", SeekerProof(key, simulated = true)))
    assertEquals("This host didn't prove it's a Seeker.", hardware(phone).verifier.problem("session-1", null))
  }
}
