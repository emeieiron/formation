package xyz.mcxross.formation.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Base64
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.link.memoryLink
import xyz.mcxross.formation.model.*

@OptIn(ExperimentalCoroutinesApi::class)
class CompletionRecoveryTest {
  private val keys = List(3) { Ed25519KeyPair.generate() }
  private val players = keys.mapIndexed { i, key ->
    Player(PlayerId("p${i + 1}"), "Phone $i", i, i == 0, Base58.encode(key.publicKey), device = "device$i")
  }
  private val info = FormationInfo("saved-session", "K7QX", "Phone 0", Opportunity(
    OpportunityId("0f8fad5b-d9cb-469f-a165-70867728950e"), ChallengeId("tap"), Skr.of(600), 3,
    5_000, Difficulty.NORMAL, Long.MAX_VALUE, "Test"))

  private fun partial(): SessionSnapshot {
    val result = RoundResult("Everyone tapped", endedAt = 5_000)
    val seal = Sealing.seal(info.opportunity, info.session, players, result)
    val first = players.first().id
    val signature = Base58.encode(keys.first().sign(Base64.decode(seal.message)))
    return SessionSnapshot(info, players, Stage.Won(result,
      seal.copy(signed = listOf(first), signatures = mapOf(first to signature)), Unlock.Waiting), 1)
  }

  @Test
  fun aPartiallySealedWinRestoresItsRosterAndCollectsOnlyMissingAcknowledgements() = runTest {
    val saved = FormationJson.decodeFromString(SessionSnapshot.serializer(),
      FormationJson.encodeToString(SessionSnapshot.serializer(), partial()))
    var checkpoint = saved
    val host = FormationHost(info, TapChallenge, backgroundScope, Clock { testScheduler.currentTime },
      recovery = saved, checkpoint = { checkpoint = it })
    players.forEachIndexed { i, player ->
      val client = FormationClient(PlayerIdentity(player.device!!, player.name, i, keys[i], wallet = if (i == 1) Base58.encode(ByteArray(32) { 9 }) else null, formats = mapOf("tap" to 1)), connect = {
        val (phone, server) = memoryLink()
        backgroundScope.launch { host.serve(server, local = i == 0) }
        phone
      }, scope = backgroundScope, clock = Clock { testScheduler.currentTime }, recovery = saved)
      client.start()
    }
    runCurrent()
    val won = assertIs<Stage.Won>(host.snapshot.value.stage)
    assertTrue(won.seal.complete)
    assertEquals(saved.players.map { it.id }, host.snapshot.value.players.map { it.id })
    assertEquals((saved.stage as Stage.Won).seal.root, won.seal.root)
    assertEquals(won.seal, assertIs<Stage.Won>(checkpoint.stage).seal)
    assertEquals(won, validatedCompletion(checkpoint))
  }

  @Test
  fun corruptedAcknowledgementsCannotRestoreACompletedRound() {
    val saved = partial()
    val won = saved.stage as Stage.Won
    assertFailsWith<IllegalArgumentException> {
      validatedCompletion(saved.copy(stage = won.copy(seal = won.seal.copy(signatures = emptyMap()))))
    }
  }

  @Test
  fun aFailedDurableWriteIsReportedWithoutKillingTheSession() = runTest {
    val host = FormationHost(info, TapChallenge, backgroundScope, recovery = partial(),
      checkpoint = { error("Disk full") })
    host.unlockFailed("Offline")
    runCurrent()
    val won = assertIs<Stage.Won>(host.snapshot.value.stage)
    assertTrue(won.storageProblem != null)
    assertIs<Unlock.Failed>(won.unlock)
  }
}
