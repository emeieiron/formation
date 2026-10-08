package xyz.mcxross.formation.state

import kotlin.test.*
import xyz.mcxross.formation.crypto.*
import xyz.mcxross.formation.model.*
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.session.*

class CompletedSessionsTest {
  @Test
  fun anOlderClientSnapshotCannotUndoVerifiedSealsOrPayment() {
    val data = mutableMapOf<String, String>()
    val store =
      object : KeyValueStore {
        override fun get(key: String) = data[key]

        override fun put(key: String, value: String?) {
          if (value == null) data.remove(key) else data[key] = value
        }
      }
    val keys = List(2) { Ed25519KeyPair.generate() }
    val players = keys.mapIndexed { i, key ->
      Player(PlayerId("p$i"), "Phone", i, i == 0, Base58.encode(key.publicKey))
    }
    val info =
      FormationInfo(
        "session",
        "CODE",
        "Host",
        Opportunity(
          Budget(
            OpportunityId("So11111111111111111111111111111111111111112"),
            "11111111111111111111111111111111",
            "sgt",
            Skr.of(120),
            3,
            31,
            Long.MAX_VALUE,
            "Test",
          ),
          ChallengeId("sync"),
          2,
        ),
      )
    val result = RoundResult("Won", endedAt = 100)
    val seal = Sealing.seal(info.opportunity, info.session, players, result)
    val signatures =
      players.zip(keys).associate { (player, key) ->
        player.id to Base58.encode(key.sign(Base64.decode(seal.message)))
      }
    val saved = SessionSnapshot(info, players, Stage.Won(result, seal, Unlock.Waiting), 1)
    val complete =
      saved.copy(
        stage =
          Stage.Won(
            result,
            seal.copy(signed = players.map { it.id }, signatures = signatures),
            Unlock.Unlocked("receipt", 200, paid = listOf(players.last().id)),
          )
      )
    val journal = CompletedSessions(store)
    journal.remember(complete)
    journal.remember(saved)
    val recovered = CompletedSessions(store).entries.value.single().snapshot
    assertTrue((recovered.stage as Stage.Won).seal.complete)
    assertIs<Unlock.Unlocked>((recovered.stage as Stage.Won).unlock)
    validatedCompletion(recovered)
    val ledger = TicketLedger(store)
    val ownerWallet = Base58.encode(keys.first().publicKey)
    SettlementCompletion(journal, ledger)
      .record(
        PendingUnlock(info.opportunity, (complete.stage as Stage.Won).seal, 100),
        UnlockReceipt("receipt", null, listOf(players.last().id)),
        SeekerIdentity(ownerWallet, "Sgt111", "", "", test = true),
        200,
      )
    val restoredOwnerReceipt = TicketLedger(store).tickets.value.single()
    assertEquals(ownerWallet, restoredOwnerReceipt.claimedTo)
    assertEquals("receipt", restoredOwnerReceipt.claimReceipt)
  }
}
