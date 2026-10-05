package xyz.mcxross.formation.session

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.link.memoryLink
import xyz.mcxross.formation.model.Budget
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.model.OpportunityId
import xyz.mcxross.formation.model.Skr

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenReadinessTest {
  private val opportunity = Opportunity(Budget(OpportunityId("entry"), "contest", "sgt", Skr.of(600), 3, 31, Long.MAX_VALUE, "Test"), ChallengeId("tap"), players = 2)
  private val phone = ScreenProfile(70.0, 150.0, 16.5, ScreenInsets(1.5, 3.0, 1.5, 2.0))
  private val tiny = ScreenProfile(40.0, 70.0, 12.0)

  private class Rules : ChallengeRules<TapChallenge.State, TapChallenge.Tap> by TapChallenge {
    var setup: ChallengeSetup? = null
    override fun requiredCapabilities(players: Int) = setOf(ScreenRequirement.CAPABILITY)
    override fun activeCapabilities(state: TapChallenge.State, player: xyz.mcxross.formation.model.PlayerId, players: Int) =
      emptySet<String>()
    override fun screenRequirement(players: Int) = ScreenRequirement(minShortMm = 45.0, minLongMm = 90.0)
    override fun newGame(setup: ChallengeSetup) = TapChallenge.newGame(setup).also { this.setup = setup }
  }

  private fun TestScope.host(rules: Rules) = FormationHost(
    FormationInfo("session-1", "K7QX", "Aaron", opportunity), rules, backgroundScope,
    Clock { testScheduler.currentTime }, Random(1), HostTiming(briefingMs = 5_000, countdownMs = 1_000),
  )

  private fun TestScope.join(host: FormationHost, name: String, seeker: Boolean, capabilities: Set<String>) =
    FormationClient(
      PlayerIdentity(name, name, 0, Ed25519KeyPair.generate(), formats = mapOf("tap" to 1), capabilities = capabilities),
      connect = {
        val (phone, seekerSide) = memoryLink()
        backgroundScope.launch { host.serve(seekerSide, local = seeker) }
        phone
      },
      scope = backgroundScope,
      clock = Clock { testScheduler.currentTime },
    ).also { it.start() }

  @Test
  fun phonesThatCannotMeasureTheirScreenAreTurnedAway() = runTest {
    val host = host(Rules())
    join(host, "Aaron", seeker = true, setOf(ScreenRequirement.CAPABILITY))
    runCurrent()
    val guest = join(host, "Maya", seeker = false, emptySet())
    runCurrent()
    assertEquals(FormationClient.Status.Rejected(Rejection.CAPABILITY), guest.status.value)
  }

  @Test
  fun readinessWaitsForAnAcceptedScreenAndTheSetupKeepsIt() = runTest {
    val rules = Rules()
    val host = host(rules)
    val phones = listOf(join(host, "Aaron", true, setOf(ScreenRequirement.CAPABILITY)),
      join(host, "Maya", false, setOf(ScreenRequirement.CAPABILITY)))
    runCurrent()
    host.begin()
    runCurrent()
    assertIs<Stage.Briefing>(host.snapshot.value.stage)

    fun ready(index: Int) = host.snapshot.value.players[index].sensorReady
    phones[0].sensors(0, setOf(ScreenRequirement.CAPABILITY), null)
    phones[1].sensors(0, setOf(ScreenRequirement.CAPABILITY), tiny)
    runCurrent()
    assertFalse(ready(0), "A capable phone still needs a measurement")
    assertFalse(ready(1), "A screen below the requirement is not ready")

    phones[0].sensors(0, setOf(ScreenRequirement.CAPABILITY), phone)
    phones[1].sensors(0, setOf(ScreenRequirement.CAPABILITY), phone.copy(pxPerMm = 18.0))
    runCurrent()
    assertTrue(ready(0) && ready(1))

    phones.forEach { it.ready(true) }
    runCurrent()
    assertIs<Stage.Playing>(host.snapshot.value.stage)
    val ids = host.snapshot.value.players.map { it.id }
    assertEquals(mapOf(ids[0] to phone, ids[1] to phone.copy(pxPerMm = 18.0)), rules.setup!!.screens)
  }

  @Test
  fun profilesRejectReadingsNoScreenCouldProduce() {
    assertTrue(phone.plausible)
    assertEquals(67.0, phone.usableWidthMm)
    assertEquals(145.0, phone.usableHeightMm)
    assertFalse(phone.copy(pxPerMm = 1.0).plausible)
    assertFalse(phone.copy(widthMm = Double.NaN).plausible)
    assertFalse(phone.copy(insets = ScreenInsets(left = 40.0)).plausible)
    assertFalse(phone.copy(insets = ScreenInsets(top = -1.0)).plausible)
    val requirement = ScreenRequirement(minShortMm = 45.0, minLongMm = 90.0)
    assertTrue(requirement.accepts(phone))
    assertFalse(requirement.accepts(tiny))
    assertFalse(requirement.accepts(null))
  }
}
