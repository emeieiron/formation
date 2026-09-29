package xyz.mcxross.formation.session

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.crypto.RosterTree
import xyz.mcxross.formation.crypto.hexToBytes
import xyz.mcxross.formation.link.LinkChannel
import xyz.mcxross.formation.link.memoryLink
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.model.OpportunityId
import xyz.mcxross.formation.model.Skr

@OptIn(ExperimentalCoroutinesApi::class)
class FormationSessionTest {
  private val opportunity =
    Opportunity(
      id = OpportunityId("0f8fad5b-d9cb-469f-a165-70867728950e"),
      challenge = ChallengeId("tap"),
      reward = Skr.of(600),
      players = 3,
      ownerBps = 5_000,
      difficulty = Difficulty.NORMAL,
      expiresAt = Long.MAX_VALUE,
      sponsor = "Test",
    )

  private inner class Formation(val test: TestScope) {
    val clock = Clock { test.testScheduler.currentTime }
    val host =
      FormationHost(
        FormationInfo("session-1", "K7QX", "Aaron", opportunity),
        TapChallenge,
        test.backgroundScope,
        clock,
        Random(1),
        HostTiming(briefingMs = 5_000, countdownMs = 1_000),
      )

    val links = mutableMapOf<String, LinkChannel>()

    fun join(
      name: String,
      seeker: Boolean = false,
      device: String = name,
      key: Ed25519KeyPair = Ed25519KeyPair.generate(),
      wallet: String? = null,
    ): FormationClient {
      val client =
        FormationClient(
          PlayerIdentity(device, name, 0, key, wallet),
          connect = {
            val (phone, seekerSide) = memoryLink()
            links[device] = seekerSide
            test.backgroundScope.launch { host.serve(seekerSide, local = seeker) }
            phone
          },
          scope = test.backgroundScope,
          clock = clock,
        )
      client.start()
      return client
    }

    val stage: Stage
      get() = host.snapshot.value.stage
  }

  private fun tap(wrong: Boolean = false) = buildJsonObject { put("wrong", JsonPrimitive(wrong)) }

  @Test
  fun aFullFormationPlaysWinsSealsAndUnlocks() = runTest {
    val f = Formation(this)
    val aaron = f.join("Aaron", seeker = true)
    runCurrent()
    val maya = f.join("Maya")
    val kofi = f.join("Kofi")
    runCurrent()

    val players = f.host.snapshot.value.players
    assertEquals(listOf("Aaron", "Maya", "Kofi"), players.map { it.name })
    assertTrue(players.first().seeker)
    assertEquals(players[1].id, maya.me.value)

    f.host.begin()
    runCurrent()
    assertIs<Stage.Briefing>(maya.snapshot.value!!.stage)

    val phones = listOf(aaron, maya, kofi)
    phones.forEach { it.ready(true) }
    runCurrent()
    val playing = assertIs<Stage.Playing>(f.stage)
    assertEquals(1, f.host.snapshot.value.round)
    assertNotNull(kofi.frame.value, "phones get the opening state during the countdown")

    advanceTimeBy(playing.goAt - testScheduler.currentTime + 1)
    repeat(2) {
      phones.forEach { it.play(tap()) }
      runCurrent()
    }

    val won = assertIs<Stage.Won>(f.stage)
    assertEquals(Skr.of(300), won.seal.ownerAmount)
    assertEquals(listOf(Skr.of(150), Skr.of(150)), won.seal.roster.map { it.amount })
    assertEquals(listOf(players[1].id, players[2].id), won.seal.roster.map { it.player })
    assertTrue(won.seal.complete, "every phone signs the seal on its own")

    val root = won.seal.root.hexToBytes()
    for (share in won.seal.roster) {
      val proof = assertNotNull(Sealing.proof(won.seal, share.player))
      assertTrue(
        RosterTree.verify(
          share.index,
          Base58.decode(share.claimKey),
          share.wallet?.let(Base58::decode),
          proof,
          root,
        )
      )
    }

    f.host.unlocked("5ig", null)
    runCurrent()
    assertIs<Unlock.Unlocked>(assertIs<Stage.Won>(maya.snapshot.value!!.stage).unlock)
  }

  @Test
  fun helpersWalletsAreSealedIntoTheRoster() = runTest {
    val f = Formation(this)
    val mayaWallet = Base58.encode(ByteArray(32) { 3 })
    val kofiWallet = Base58.encode(ByteArray(32) { 4 })
    val aaron = f.join("Aaron", seeker = true, wallet = Base58.encode(ByteArray(32) { 9 }))
    runCurrent()
    val maya = f.join("Maya", wallet = mayaWallet)
    val kofi = f.join("Kofi")
    runCurrent()
    kofi.setWallet("not a wallet")
    runCurrent()
    assertEquals(null, f.host.snapshot.value.players[2].wallet)
    kofi.setWallet(kofiWallet)
    runCurrent()
    assertEquals(
      listOf(mayaWallet, kofiWallet),
      f.host.snapshot.value.players.drop(1).map { it.wallet },
    )

    f.host.begin()
    runCurrent()
    val phones = listOf(aaron, maya, kofi)
    phones.forEach { it.ready(true) }
    runCurrent()
    val playing = assertIs<Stage.Playing>(f.stage)
    advanceTimeBy(playing.goAt - testScheduler.currentTime + 1)
    repeat(2) {
      phones.forEach { it.play(tap()) }
      runCurrent()
    }
    val won = assertIs<Stage.Won>(f.stage)
    assertEquals(listOf(mayaWallet, kofiWallet), won.seal.roster.map { it.wallet })
    assertTrue(won.seal.complete)

    kofi.setWallet(null)
    runCurrent()
    assertEquals(
      kofiWallet,
      f.host.snapshot.value.players[2].wallet,
      "wallets are frozen once sealed",
    )

    val paid = won.seal.roster.map { it.player }
    f.host.unlocked("5ig", null, paid)
    runCurrent()
    assertEquals(
      paid,
      assertIs<Unlock.Unlocked>(assertIs<Stage.Won>(maya.snapshot.value!!.stage).unlock).paid,
    )
  }

  @Test
  fun namesAndLightsCanChangeOnlyInTheLobby() = runTest {
    val f = Formation(this)
    val aaron = f.join("Aaron", seeker = true)
    runCurrent()
    val maya = f.join("Maya")
    f.join("Kofi")
    runCurrent()
    maya.setProfile("  Maya B  ", 11)
    runCurrent()
    val renamed = f.host.snapshot.value.players[1]
    assertEquals("Maya B" to 3, renamed.name to renamed.light)

    f.host.begin()
    runCurrent()
    maya.setProfile("Late", 0)
    runCurrent()
    assertEquals("Maya B", f.host.snapshot.value.players[1].name)
    assertIs<Stage.Briefing>(aaron.snapshot.value!!.stage)
  }

  @Test
  fun aBrokenAttemptCanBeRunBack() = runTest {
    val f = Formation(this)
    val phones = listOf(f.join("Aaron", seeker = true), f.join("Maya"), f.join("Kofi"))
    runCurrent()
    f.host.begin()
    runCurrent()
    phones.forEach { it.ready(true) }
    runCurrent()
    advanceTimeBy(1_100)
    phones[2].play(tap(wrong = true))
    runCurrent()

    val lost = assertIs<Stage.Lost>(f.stage)
    assertEquals(phones[2].me.value, lost.result.culprit)

    f.host.runItBack()
    runCurrent()
    assertIs<Stage.Briefing>(f.stage)
    phones.forEach { it.ready(true) }
    runCurrent()
    assertIs<Stage.Playing>(f.stage)
    assertEquals(2, f.host.snapshot.value.round)
  }

  @Test
  fun theBriefingStartsOnItsOwn() = runTest {
    val f = Formation(this)
    listOf(f.join("Aaron", seeker = true), f.join("Maya"), f.join("Kofi"))
    runCurrent()
    f.host.begin()
    runCurrent()
    advanceTimeBy(5_100)
    assertIs<Stage.Playing>(f.stage)
  }

  @Test
  fun fullStartedAndDuplicatePhonesAreTurnedAway() = runTest {
    val f = Formation(this)
    f.join("Aaron", seeker = true)
    val key = Ed25519KeyPair.generate()
    f.join("Maya", key = key)
    runCurrent()

    val twin = f.join("Maya's twin", key = key)
    runCurrent()
    assertEquals(FormationClient.Status.Rejected(Rejection.DUPLICATE), twin.status.value)

    f.join("Kofi")
    runCurrent()
    val late = f.join("Late")
    runCurrent()
    assertEquals(FormationClient.Status.Rejected(Rejection.FULL), late.status.value)

    f.host.begin()
    runCurrent()
    val afterStart = f.join("After")
    runCurrent()
    assertEquals(FormationClient.Status.Rejected(Rejection.STARTED), afterStart.status.value)
  }

  @Test
  fun aRemotePhoneCannotTakeTheSeekersSeat() = runTest {
    val f = Formation(this)
    f.join("Aaron", seeker = true, device = "seeker-device")
    runCurrent()
    val impostor = f.join("Impostor", device = "seeker-device")
    runCurrent()
    assertEquals(FormationClient.Status.Rejected(Rejection.DUPLICATE), impostor.status.value)
  }

  @Test
  fun aPhoneThatDropsComesBackToItsSeat() = runTest {
    val f = Formation(this)
    val phones = listOf(f.join("Aaron", seeker = true), f.join("Maya"), f.join("Kofi"))
    runCurrent()
    f.host.begin()
    runCurrent()
    phones.forEach { it.ready(true) }
    runCurrent()
    val maya = phones[1]
    val seat = maya.me.value

    f.links.getValue("Maya").close()
    runCurrent()
    assertEquals(false, f.host.snapshot.value.player(seat)!!.connected)

    advanceTimeBy(1_000)
    assertEquals(
      FormationClient.Status.Joined,
      maya.status.first { it == FormationClient.Status.Joined },
    )
    assertEquals(seat, maya.me.value)
    assertEquals(true, f.host.snapshot.value.player(seat)!!.connected)
    assertEquals(3, f.host.snapshot.value.players.size)
  }

  @Test
  fun lobbyLeaversFreeTheirSeat() = runTest {
    val f = Formation(this)
    f.join("Aaron", seeker = true)
    val maya = f.join("Maya")
    runCurrent()
    maya.leave()
    runCurrent()
    advanceTimeBy(500)
    assertEquals(listOf("Aaron"), f.host.snapshot.value.players.map { it.name })
    assertIs<FormationClient.Status.Ended>(maya.status.value)
  }

  @Test
  fun theBeaconDescribesTheLobby() = runTest {
    val f = Formation(this)
    f.join("Aaron", seeker = true)
    runCurrent()
    val beacon = FormationJson.decodeFromString(Beacon.serializer(), f.host.beacon())
    assertEquals("K7QX", beacon.code)
    assertEquals(1, beacon.joined)
    assertEquals(3, beacon.players)
    assertTrue(beacon.open)
    assertEquals(Skr.of(150), beacon.helperShare)
  }

  @Test
  fun closingEndsEveryPhone() = runTest {
    val f = Formation(this)
    val phones = listOf(f.join("Aaron", seeker = true), f.join("Maya"))
    runCurrent()
    f.host.close("Done for today")
    runCurrent()
    phones.forEach { assertEquals(FormationClient.Status.Ended("Done for today"), it.status.value) }
  }
}
