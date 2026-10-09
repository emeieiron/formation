package xyz.mcxross.formation.caravan

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.crypto.RosterTree
import xyz.mcxross.formation.crypto.hexToBytes
import xyz.mcxross.formation.link.LinkChannel
import xyz.mcxross.formation.link.memoryLink
import xyz.mcxross.formation.model.Budget
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.model.OpportunityId
import xyz.mcxross.formation.model.Skr
import xyz.mcxross.formation.session.Clock
import xyz.mcxross.formation.session.FormationClient
import xyz.mcxross.formation.session.FormationHost
import xyz.mcxross.formation.session.FormationInfo
import xyz.mcxross.formation.session.FormationJson
import xyz.mcxross.formation.session.HostTiming
import xyz.mcxross.formation.session.PlayerIdentity
import xyz.mcxross.formation.session.Sealing
import xyz.mcxross.formation.session.Stage
import xyz.mcxross.formation.session.Unlock

@OptIn(ExperimentalCoroutinesApi::class)
class CaravanSessionTest {
  private class Formation(val test: TestScope) {
    var now = 1_000L
    val clock = Clock { now }
    val opportunity =
      Opportunity(
        Budget(
          OpportunityId("caravan-entry"),
          "contest",
          "sgt",
          Skr.of(600),
          15,
          31,
          Long.MAX_VALUE,
          "Test",
        ),
        Caravan.id,
        players = 6,
      )
    val host =
      FormationHost(
        FormationInfo("caravan-session", "K7QX", "Theo", opportunity),
        Caravan,
        test.backgroundScope,
        clock,
        Random(1),
        HostTiming(briefingMs = 5_000, countdownMs = 1_000),
      )
    val links = mutableMapOf<Int, LinkChannel>()
    val phones =
      (0 until 6).map { index ->
        FormationClient(
            PlayerIdentity(
              "device-$index",
              "Walker $index",
              index,
              Ed25519KeyPair.generate(),
              formats = mapOf("caravan" to 1),
            ),
            connect = {
              val (phone, hostSide) = memoryLink()
              links[index] = hostSide
              test.backgroundScope.launch { host.serve(hostSide, local = index == 0) }
              phone
            },
            test.backgroundScope,
            clock,
          )
          .also { it.start() }
      }

    fun state(phone: FormationClient) =
      FormationJson.decodeFromJsonElement(
        Caravan.stateSerializer,
        assertNotNull(phone.frame.value).state,
      )

    fun stride(phone: FormationClient, index: Int) =
      phone.play(FormationJson.encodeToJsonElement(Caravan.inputSerializer, Stride(index, now)))
  }

  private fun TestScope.begin(f: Formation) {
    runCurrent()
    f.host.begin()
    runCurrent()
    f.phones.forEach { it.ready(true) }
    runCurrent()
    f.now = assertIs<Stage.Playing>(f.host.snapshot.value.stage).goAt + 1
  }

  @Test
  fun sixPlayersCompleteTheUnchangedTargetSealEveryShareAndReceiveUnlockUpdates() =
    runTest(timeout = 10.minutes) {
      val f = Formation(this)
      begin(f)
      // Control only the test clock. Every accepted stride still passes through the real client,
      // transport, host, serializers and Caravan game; the production target and rules are
      // unchanged.
      for (step in 1..15_000) {
        f.phones.forEach { f.stride(it, step) }
        runCurrent()
        if (step == 14_999) {
          assertIs<Stage.Playing>(f.host.snapshot.value.stage)
          f.phones.forEach { assertEquals(14_999, f.state(it).minSteps) }
        }
        f.now += 350L
      }
      val won = assertIs<Stage.Won>(f.host.snapshot.value.stage)
      assertTrue(won.seal.complete)
      assertEquals(5, won.seal.roster.size)
      assertEquals(
        f.opportunity.reward,
        won.seal.ownerAmount + won.seal.roster.map { it.amount }.reduce { a, b -> a + b },
      )
      f.phones.forEach { assertIs<Stage.Won>(it.snapshot.value!!.stage) }
      won.seal.roster.forEach { share ->
        val proof = assertNotNull(Sealing.proof(won.seal, share.player))
        assertTrue(
          RosterTree.verify(
            share.index,
            Base58.decode(share.claimKey),
            share.wallet?.let(Base58::decode),
            proof,
            won.seal.root.hexToBytes(),
          )
        )
      }
      f.host.unlocking()
      runCurrent()
      f.host.unlockFailed("Temporarily offline")
      runCurrent()
      f.phones.forEach {
        assertIs<Unlock.Failed>(assertIs<Stage.Won>(it.snapshot.value!!.stage).unlock)
      }
      f.host.unlocking()
      f.host.unlocked("test-receipt", null)
      runCurrent()
      f.phones.forEach {
        assertIs<Unlock.Unlocked>(assertIs<Stage.Won>(it.snapshot.value!!.stage).unlock)
      }
    }

  @Test
  fun interruptedWalkingStopsInputsAndReconnectAllowsANewRoundWithTheSameSeats() = runTest {
    val f = Formation(this)
    begin(f)
    f.phones.forEach { f.stride(it, 1) }
    runCurrent()
    val ids = f.phones.map { it.me.value }
    f.links.getValue(1).close()
    runCurrent()
    f.now += 500L
    f.stride(f.phones.first(), 2)
    runCurrent()
    assertEquals(1, f.state(f.phones.first()).maxSteps)
    advanceTimeBy(1_000L)
    runCurrent()
    assertIs<Stage.Lost>(f.host.snapshot.value.stage)
    assertEquals(ids, f.phones.map { it.me.value })
    f.host.runItBack()
    runCurrent()
    f.phones.forEach { it.ready(true) }
    runCurrent()
    val playing = assertIs<Stage.Playing>(f.host.snapshot.value.stage)
    assertEquals(2, f.host.snapshot.value.round)
    f.now = playing.goAt + 1
    f.phones.forEach { assertEquals(0, f.state(it).minSteps) }
    f.phones.forEach { f.stride(it, 1) }
    runCurrent()
    f.phones.forEach { assertEquals(1, f.state(it).minSteps) }
  }
}
