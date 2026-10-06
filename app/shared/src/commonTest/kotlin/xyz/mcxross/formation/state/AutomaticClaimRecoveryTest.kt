package xyz.mcxross.formation.state

import kotlin.test.*
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.platform.SecretStore
import xyz.mcxross.formation.state.recovery.ClaimIdentity
import xyz.mcxross.formation.state.recovery.ClaimKeyState

class AutomaticClaimRecoveryTest {
  private class Store : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun get(key: String) = values[key]
    override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
  }

  private class Secrets : SecretStore {
    var primary: ByteArray? = null
    var backup: ByteArray? = null
    var failWrite = false
    var failProtection = false
    override fun contains(name: String) = primary != null || backup != null
    override fun get(name: String) = primary?.copyOf()
    override fun put(name: String, value: ByteArray) { check(!failWrite) { "Disk full" }; primary = value.copyOf() }
    override fun recoveryCopy(name: String) = backup?.copyOf()
    override fun protect(name: String, value: ByteArray): Boolean {
      if (failProtection) error("Protected storage unavailable")
      backup = value.copyOf()
      return true
    }
  }

  @Test
  fun repairsCorruptAndMissingPrimaryCopiesWithoutChangingIdentity() {
    for (damaged in listOf(null, byteArrayOf(1), Ed25519KeyPair.generate().seed)) {
      val store = Store()
      val secrets = Secrets()
      val original = ClaimIdentity(store, secrets)
      val seed = original.key.seed.copyOf()
      store.put("sol.tickets", "saved-entitlement")
      secrets.primary = damaged
      val repaired = ClaimIdentity(store, secrets)
      assertEquals(original.address, repaired.address)
      assertTrue(assertIs<ClaimKeyState.Ready>(repaired.status.value).protectedOnDevice)
      assertContentEquals(seed, repaired.key.seed)
      assertContentEquals(seed, secrets.primary)
      assertEquals("saved-entitlement", store.get("sol.tickets"))
    }
  }

  @Test
  fun refusesInvalidAndDifferentIdentityRecoveryCopies() {
    for (wrong in listOf(byteArrayOf(1), Ed25519KeyPair.generate().seed)) {
      val store = Store()
      val secrets = Secrets()
      val original = ClaimIdentity(store, secrets)
      secrets.primary = byteArrayOf(2)
      secrets.backup = wrong.copyOf()
      val blocked = ClaimIdentity(store, secrets)
      assertIs<ClaimKeyState.Missing>(blocked.status.value)
      assertEquals(original.address, blocked.address)
      assertContentEquals(byteArrayOf(2), secrets.primary)
      assertContentEquals(wrong, secrets.backup)
    }
  }

  @Test
  fun anInterruptedRepairKeepsTheRecoveryCopyAndRetriesOnRelaunch() {
    val store = Store()
    val secrets = Secrets()
    val original = ClaimIdentity(store, secrets)
    val seed = original.key.seed.copyOf()
    secrets.primary = null
    secrets.failWrite = true
    assertIs<ClaimKeyState.Missing>(ClaimIdentity(store, secrets).status.value)
    assertContentEquals(seed, secrets.backup)
    secrets.failWrite = false
    assertEquals(original.address, ClaimIdentity(store, secrets).address)
    assertContentEquals(seed, secrets.primary)
  }

  @Test
  fun aHealthyExistingKeyIsProtectedWithoutBlockingUseIfProtectionFails() {
    val store = Store()
    val secrets = Secrets().apply { primary = Ed25519KeyPair.generate().seed; failProtection = true }
    val original = ClaimIdentity(store, secrets)
    assertFalse(assertIs<ClaimKeyState.Ready>(original.status.value).protectedOnDevice)
    assertNull(secrets.backup)
    secrets.failProtection = false
    val protected = ClaimIdentity(store, secrets)
    assertEquals(original.address, protected.address)
    assertTrue(assertIs<ClaimKeyState.Ready>(protected.status.value).protectedOnDevice)
    assertContentEquals(original.key.seed, secrets.backup)
  }
}
