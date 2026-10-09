package xyz.mcxross.formation.state

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.link.memoryLink
import xyz.mcxross.formation.longshot.Longshot
import xyz.mcxross.formation.longshot.LongshotInput
import xyz.mcxross.formation.longshot.LongshotPhase
import xyz.mcxross.formation.longshot.LongshotState
import xyz.mcxross.formation.longshot.Prediction
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.model.OpportunityId
import xyz.mcxross.formation.session.Clock
import xyz.mcxross.formation.session.FormationClient
import xyz.mcxross.formation.session.FormationHost
import xyz.mcxross.formation.session.FormationInfo
import xyz.mcxross.formation.session.FormationJson
import xyz.mcxross.formation.session.HostTiming
import xyz.mcxross.formation.session.PlayerIdentity
import xyz.mcxross.formation.session.Stage
import xyz.mcxross.formation.solana.ore.DepositVerification
import xyz.mcxross.formation.solana.ore.OreDeposit
import xyz.mcxross.formation.solana.ore.OreMiningObserver
import xyz.mcxross.formation.solana.ore.OreRoundResult

@OptIn(ExperimentalCoroutinesApi::class)
class LongshotRoundsTest {
  @Test
  fun lockedPredictionsSurviveRpcFailureAndSettleTheSameRoundOnEveryPhone() = runTest {
    var boardId = 100L
    var offline = false
    var calls = 0
    val requestedRounds = mutableListOf<Long>()
    val reader =
      object : OreMiningObserver {
        override suspend fun fundingRound(): Long {
          calls++
          check(!offline)
          return boardId
        }

        override suspend fun verifyDeposit(
          deposit: OreDeposit,
          signature: String,
          validUntil: Long,
        ): DepositVerification {
          check(!offline)
          assertEquals(100L, deposit.round)
          assertEquals(17, deposit.number)
          assertEquals(1_000_000L, deposit.lamports)
          assertEquals("room/1/1", deposit.reference)
          return DepositVerification.Confirmed
        }

        override suspend fun result(id: Long): OreRoundResult {
          calls++
          requestedRounds += id
          check(!offline)
          return if (boardId > id) OreRoundResult.Resolved(17) else OreRoundResult.Pending
        }
      }
    val clock = Clock { testScheduler.currentTime }
    val host =
      FormationHost(
        FormationInfo(
          "room",
          "ABCD",
          "Host",
          Opportunity(null, Longshot.id, 3, socialId = OpportunityId("room")),
        ),
        Longshot,
        backgroundScope,
        clock,
        Random(4),
        HostTiming(countdownMs = 1000),
      )
    val phones =
      (0..2).map { index ->
        FormationClient(
            PlayerIdentity(
              "device$index",
              "Player $index",
              index,
              Ed25519KeyPair.generate(),
              formats = mapOf("longshot" to Longshot.formatVersion),
            ),
            connect = {
              val (phone, server) = memoryLink()
              backgroundScope.launch { host.serve(server, local = index == 0) }
              phone
            },
            scope = backgroundScope,
            clock = clock,
          )
          .also { it.start() }
      }
    runCurrent()
    LongshotRounds(
      host,
      phones[0],
      reader,
      backgroundScope,
    )
    host.begin()
    runCurrent()
    phones.forEach { it.ready(true) }
    runCurrent()
    val playing = assertIs<Stage.Playing>(host.snapshot.value.stage)
    advanceTimeBy(playing.goAt - testScheduler.currentTime + 1)
    runCurrent()
    fun state(phone: FormationClient = phones[0]) =
      FormationJson.decodeFromJsonElement(LongshotState.serializer(), phone.frame.value!!.state)
    fun send(phone: FormationClient, input: LongshotInput) =
      phone.play(FormationJson.encodeToJsonElement(LongshotInput.serializer(), input))
    val picker = state().picker
    send(phones.first { it.me.value == picker }, LongshotInput.Pick(1, 17))
    runCurrent()
    val voters = phones.filter { it.me.value != picker }
    send(voters[0], LongshotInput.Predict(1, Prediction.WIN))
    runCurrent()
    assertTrue(state(voters[1]).predictions.isEmpty())
    send(voters[1], LongshotInput.Predict(1, Prediction.LOSE))
    runCurrent()
    advanceTimeBy(4000)
    runCurrent()
    assertEquals(100L, state().oreRound)
    assertEquals(LongshotPhase.Funding, state().phase)
    assertTrue(requestedRounds.isEmpty())
    send(
      phones.first { it.me.value == picker },
      LongshotInput.Submitted(1, "11111111111111111111111111111111", "s".repeat(88), 500),
    )
    runCurrent()
    advanceTimeBy(4000)
    runCurrent()
    assertEquals(LongshotPhase.Watching, state().phase)
    offline = true
    advanceTimeBy(4000)
    runCurrent()
    assertFalse(state().connected)
    assertEquals(100L, state().oreRound)
    offline = false
    boardId = 102
    advanceTimeBy(4000)
    runCurrent()
    phones.forEach { phone ->
      assertEquals(LongshotPhase.Result, state(phone).phase)
      assertEquals(Prediction.WIN, state(phone).outcome)
      assertEquals(2, state(phone).predictions.size)
      assertIs<Stage.Playing>(phone.snapshot.value!!.stage)
    }
    assertEquals(1, requestedRounds.distinct().size)
    send(phones[0], LongshotInput.Next(1))
    runCurrent()
    assertEquals(2, state().turn)
    assertEquals(LongshotPhase.Choosing, state().phase)
    assertTrue(state().predictions.isEmpty())
    host.close()
    runCurrent()
    val before = calls
    advanceTimeBy(10_000)
    runCurrent()
    assertEquals(before, calls)
  }
}
