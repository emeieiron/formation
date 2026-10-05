package xyz.mcxross.formation.session

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.link.LinkChannel
import xyz.mcxross.formation.link.memoryLink
import xyz.mcxross.formation.model.Budget
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.model.OpportunityId
import xyz.mcxross.formation.model.Skr

@OptIn(ExperimentalCoroutinesApi::class)
class HostSessionTest {
  private val opportunity = Opportunity(Budget(OpportunityId("entry"), "contest", "sgt", Skr.of(600), 3, 31, Long.MAX_VALUE, "Test"), ChallengeId("tap"), players = 3)
  private val wallet = Ed25519KeyPair.generate()
  private val phone = Ed25519KeyPair.generate()

  private fun authorized(
    hostKey: Ed25519KeyPair = phone,
    signer: Ed25519KeyPair = wallet,
    network: String = NETWORK,
    session: String = SESSION,
  ): HostCredentials {
    val text = HostChecks.authorization(Base58.encode(hostKey.publicKey), network, "2026-10-05")
    return HostChecks.credentials(session, opportunity, Base58.encode(wallet.publicKey), phone, text,
      Base58.encode(signer.sign(text.encodeToByteArray())))
  }

  private fun verifier(allowSimulated: Boolean = false) =
    HostVerifier { session, proof -> HostChecks.problem(proof, session, NETWORK, allowSimulated) }

  @Test
  fun aWalletAuthorizedPhoneHosts() {
    assertNull(HostChecks.problem(authorized().proof, SESSION, NETWORK, allowSimulated = false))
  }

  @Test
  fun everyLinkInTheChainIsChecked() {
    fun problem(credentials: HostCredentials, session: String = SESSION) =
      HostChecks.problem(credentials.proof, session, NETWORK, allowSimulated = false)
    assertEquals("The Seeker's wallet didn't authorize this host.", problem(authorized(signer = Ed25519KeyPair.generate())))
    assertEquals("The Seeker's wallet authorized a different phone.", problem(authorized(hostKey = Ed25519KeyPair.generate())))
    assertEquals("This host is set up for another network.", problem(authorized(network = "mainnet-beta")))
    assertEquals("The host's key didn't sign this Formation.", problem(authorized(), session = "session-2"))
    assertEquals("The host's key didn't sign this Formation.",
      HostChecks.problem(authorized().proof.copy(opportunity = opportunity.copy(budget = opportunity.budget.copy(amount = Skr.of(6_000)))), SESSION, NETWORK, false))
    assertEquals("This host didn't show which Seeker it plays for.", HostChecks.problem(null, SESSION, NETWORK, false))
  }

  @Test
  fun onlyDeveloperBuildsAcceptTestSeekers() {
    val proof = HostChecks.simulated(opportunity).proof
    assertNull(HostChecks.problem(proof, SESSION, NETWORK, allowSimulated = true))
    assertEquals("This host is a test Seeker from a developer build.", HostChecks.problem(proof, SESSION, NETWORK, false))
  }

  private fun TestScope.host(credentials: HostCredentials?): FormationHost =
    FormationHost(FormationInfo(SESSION, "K7QX", "Aaron", opportunity), TapChallenge, backgroundScope,
      { testScheduler.currentTime }, Random(1), HostTiming(briefingMs = 5_000, countdownMs = 1_000), host = credentials)

  private fun TestScope.join(
    host: FormationHost,
    name: String,
    verifier: HostVerifier?,
    local: Boolean = false,
    link: (LinkChannel) -> LinkChannel = { it },
  ): FormationClient = FormationClient(
    PlayerIdentity(name, name, 0, Ed25519KeyPair.generate(), formats = mapOf("tap" to 1)),
    connect = {
      val (phone, seekerSide) = memoryLink()
      backgroundScope.launch { host.serve(seekerSide, local = local) }
      link(phone)
    },
    scope = backgroundScope,
    clock = { testScheduler.currentTime },
    verifier = verifier,
  ).also { it.start() }

  @Test
  fun guestsJoinAnAuthorizedHost() = runTest {
    val host = host(authorized())
    join(host, "Aaron", verifier = null, local = true)
    val maya = join(host, "Maya", verifier())
    runCurrent()
    assertEquals(FormationClient.Status.Joined, maya.status.value)
    assertEquals(listOf("Aaron", "Maya"), maya.snapshot.value?.players?.map { it.name })
  }

  @Test
  fun guestsRefuseAHostWithoutProof() = runTest {
    val maya = join(host(null), "Maya", verifier())
    runCurrent()
    assertEquals(FormationClient.Status.Untrusted("This host didn't show which Seeker it plays for."), maya.status.value)
    assertNull(maya.me.value)
  }

  @Test
  fun aCopiedProofIsUselessWithoutTheSessionKey() = runTest {
    // Another phone replaying a real host's proof can't sign updates with that host's session key.
    val copied = HostCredentials(authorized().proof, Ed25519KeyPair.generate())
    val maya = join(host(copied), "Maya", verifier())
    runCurrent()
    assertEquals(FormationClient.Status.Untrusted("The Seeker's updates couldn't be verified."), maya.status.value)
  }

  @Test
  fun alteredUpdatesEndTheSession() = runTest {
    val host = host(authorized())
    val maya = join(host, "Maya", verifier(), link = { phone ->
      object : LinkChannel by phone {
        override val incoming = phone.incoming.map { it.replace("Aaron", "Mallo") }
      }
    })
    join(host, "Aaron", verifier = null, local = true)
    runCurrent()
    assertEquals(FormationClient.Status.Untrusted("The Seeker's updates couldn't be verified."), maya.status.value)
  }

  @Test
  fun unsignedUpdatesAreIgnored() = runTest {
    val maya = join(host(authorized()), "Maya", verifier(), link = { phone ->
      object : LinkChannel by phone {
        override val incoming = phone.incoming.map { frame ->
          val message = FormationJson.decodeFromString(ToPlayer.serializer(), frame)
          if (message is ToPlayer.Signed) message.message else frame
        }
      }
    })
    runCurrent()
    assertEquals(FormationClient.Status.Connecting, maya.status.value)
    assertNull(maya.snapshot.value)
  }

  private companion object {
    const val SESSION = "session-1"
    const val NETWORK = "devnet"
  }
}
