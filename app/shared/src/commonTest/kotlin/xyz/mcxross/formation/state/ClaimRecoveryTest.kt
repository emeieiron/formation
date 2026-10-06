package xyz.mcxross.formation.state

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import xyz.mcxross.formation.crypto.*
import xyz.mcxross.formation.model.*
import xyz.mcxross.formation.platform.*
import xyz.mcxross.formation.state.recovery.*

class ClaimRecoveryTest {
  private class Store : KeyValueStore {
    val map = mutableMapOf<String, String>()
    var failTickets = false
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String?) {
      if (failTickets && key == "sol.tickets") error("Disk full")
      if (value == null) map.remove(key) else map[key] = value
    }
  }
  private class Secrets : SecretStore {
    val map = mutableMapOf<String, ByteArray>()
    var unreadable = false
    override fun get(name: String) = if (unreadable) null else map[name]?.copyOf()
    override fun contains(name: String) = map.containsKey(name)
    override fun put(name: String, value: ByteArray) { map[name] = value.copyOf() }
    override fun remove(name: String) { map.remove(name) }
  }
  private fun ticket(key: Ed25519KeyPair): ClaimTicket {
    val tree = RosterTree(listOf(RosterTree.Entry(key.publicKey)))
    return ClaimTicket(OpportunityId("So11111111111111111111111111111111111111112"), "11111111111111111111111111111111", ChallengeId("sync"), "Host",
      Skr.of(60), 0, tree.root.toHex(), tree.proof(0).map { it.toHex() }, 1, "unlock")
  }

  @Test
  fun anUnreadableExistingKeyIsNeverRegenerated() {
    val store = Store()
    val secrets = Secrets()
    val original = ClaimIdentity(store, secrets)
    val address = original.address
    val seed = secrets.map["claim-key"]!!.copyOf()
    secrets.unreadable = true
    val lost = ClaimIdentity(store, secrets)
    assertIs<ClaimKeyState.Missing>(lost.status.value)
    assertEquals(address, lost.address)
    assertContentEquals(seed, secrets.map["claim-key"])
    assertFailsWith<IllegalStateException> { lost.key }
  }

  @Test
  fun encryptedRecoveryRejectsTamperingAndRestoresAProofAcrossAnInterruptedWrite() = runTest {
    // iOS has no secure export provider yet and refuses rather than exporting a plain key.
    if (!recoveryCipher().available) return@runTest
    val sourceStore = Store()
    val sourceSecrets = Secrets()
    val source = ClaimIdentity(sourceStore, sourceSecrets)
    val ledger = TicketLedger(sourceStore)
    ledger.keep(ticket(source.key))
    val sourceRecovery = RecoveryService(source, ledger, sourceStore, sourceSecrets, "testnet", { true })
    val password = "a-test-recovery-password"
    val export = sourceRecovery.export(password)
    assertFalse(export.contains(Base64.encode(source.key.seed)))

    val targetStore = Store()
    val targetSecrets = Secrets()
    val target = ClaimIdentity(targetStore, targetSecrets)
    val targetLedger = TicketLedger(targetStore)
    val recovery = RecoveryService(target, targetLedger, targetStore, targetSecrets, "testnet", { true })
    val previous = target.address
    assertFailsWith<IllegalStateException> { recovery.import(export, "wrong-password") }
    val bytes = Base64.decode(export.removePrefix("formation-recovery:1:"))
    bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
    assertFailsWith<IllegalStateException> { recovery.import("formation-recovery:1:" + Base64.encode(bytes), password) }
    assertEquals(previous, target.address)
    assertTrue(targetLedger.tickets.value.isEmpty())

    targetStore.failTickets = true
    assertFailsWith<IllegalStateException> { recovery.import(export, password) }
    targetStore.failTickets = false
    val reopened = ClaimIdentity(targetStore, targetSecrets)
    val reopenedLedger = TicketLedger(targetStore)
    RecoveryService(reopened, reopenedLedger, targetStore, targetSecrets, "testnet", { false }).resumePending()
    assertEquals(source.address, reopened.address)
    assertEquals(ledger.tickets.value, reopenedLedger.tickets.value)
    assertNull(targetStore.get("claim.restore.pending"))
    assertFalse(targetSecrets.contains("claim-restore"))
  }
}
