package xyz.mcxross.formation.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.mcxross.formation.model.Budget
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.model.OpportunityId
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.model.Skr
import xyz.mcxross.formation.platform.SoundCue
import xyz.mcxross.formation.session.FormationInfo
import xyz.mcxross.formation.session.Player
import xyz.mcxross.formation.session.RoundResult
import xyz.mcxross.formation.session.Seal
import xyz.mcxross.formation.session.SessionSnapshot
import xyz.mcxross.formation.session.Stage
import xyz.mcxross.formation.session.Unlock

class SessionSoundEventsTest {
  private val host = Player(PlayerId("host"), "Theo", 0, true, "host-key")
  private val guest = Player(PlayerId("guest"), "Maya", 1, false, "guest-key")
  private val info =
    FormationInfo(
      "session",
      "A123",
      "Theo",
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
  private val lobby = SessionSnapshot(info, listOf(host), Stage.Lobby, 0)
  private val full = lobby.copy(players = listOf(host, guest))
  private val won =
    Stage.Won(
      RoundResult("Complete", endedAt = 100),
      Seal(emptyList(), Skr.of(60), "root", "seal", listOf(guest.id)),
      Unlock.Waiting,
    )

  @Test
  fun aFullConnectedGroupAnnouncesOnceDespiteProfileAndConnectionChanges() {
    val events = SessionSoundEvents()
    assertNull(events.next(lobby))
    assertNull(events.next(full.copy(players = listOf(host, guest.copy(connected = false)))))
    assertEquals(SoundCue.ASSEMBLED, events.next(full))
    assertNull(events.next(full.copy(players = listOf(host, guest.copy(name = "New name")))))
    assertNull(events.next(lobby))
    assertNull(events.next(full))
  }

  @Test
  fun eachBriefingAnnouncesOnceIncludingAnActualRetry() {
    val events = SessionSoundEvents()
    val briefing = full.copy(stage = Stage.Briefing(100))
    assertEquals(SoundCue.BEGIN, events.next(briefing))
    assertNull(events.next(briefing.copy(players = listOf(host, guest.copy(ready = true)))))
    assertNull(
      events.next(full.copy(stage = Stage.Playing(110, listOf(host.id, guest.id)), round = 1))
    )
    assertNull(
      events.next(full.copy(stage = Stage.Lost(RoundResult("Try again", endedAt = 120)), round = 1))
    )
    assertEquals(SoundCue.BEGIN, events.next(full.copy(stage = Stage.Briefing(200), round = 1)))
    assertNull(events.next(briefing))
  }

  @Test
  fun sealingAndFailedUnlockAttemptsDoNotRepeatCompletionOrAnnounceAReward() {
    val events = SessionSoundEvents()
    val result = full.copy(stage = won, round = 1)
    assertEquals(SoundCue.COMPLETE, events.next(result))
    assertNull(
      events.next(result.copy(stage = won.copy(seal = won.seal.copy(signed = listOf(guest.id)))))
    )
    assertNull(events.next(result.copy(stage = won.copy(unlock = Unlock.Unlocking))))
    assertNull(events.next(result.copy(stage = won.copy(unlock = Unlock.Failed("Offline")))))
    assertNull(events.next(result.copy(stage = won.copy(unlock = Unlock.Unlocking))))
    val unlocked = result.copy(stage = won.copy(unlock = Unlock.Unlocked("receipt", 150)))
    assertEquals(SoundCue.REWARD, events.next(unlocked))
    assertNull(events.next(unlocked))
    assertNull(events.next(result))
  }

  @Test
  fun aCoalescedWinAndUnlockPlaysOnlyTheRewardCue() {
    val events = SessionSoundEvents()
    val result = full.copy(stage = won.copy(unlock = Unlock.Unlocked("receipt", 150)), round = 1)
    assertEquals(SoundCue.REWARD, events.next(result))
    assertNull(events.next(result))
    assertNull(events.next(result.copy(stage = won)))
  }

  @Test
  fun aLaterSuccessfulRoundCanHaveItsOwnCompletionCue() {
    val events = SessionSoundEvents()
    assertEquals(SoundCue.COMPLETE, events.next(full.copy(stage = won, round = 1)))
    assertEquals(SoundCue.COMPLETE, events.next(full.copy(stage = won, round = 2)))
    assertNull(events.next(full.copy(stage = won, round = 1)))
  }
}
