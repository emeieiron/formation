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
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.model.OpportunityId
import xyz.mcxross.formation.model.Skr

@OptIn(ExperimentalCoroutinesApi::class)
class SeekerPresenceTest {
  private val opportunity = Opportunity(
    id = OpportunityId("0f8fad5b-d9cb-469f-a165-70867728950e"),
    challenge = ChallengeId("tap"),
    reward = Skr.of(600),
    players = 3,
    ownerBps = 5_000,
    difficulty = Difficulty.NORMAL,
    expiresAt = Long.MAX_VALUE,
    sponsor = "Test",
  )

  private val testSeekers = HostVerifier { session, proof ->
    SeekerPresence.problem(proof, session, SeekerPolicy("xyz.mcxross.formation", emptySet(), allowSimulated = true), 0, emptySet())
  }

  private fun presence(key: Ed25519KeyPair = Ed25519KeyPair.generate(), signer: Ed25519KeyPair = key) =
    HostPresence(SeekerProof(Base58.encode(key.publicKey), simulated = true), signer)

  private fun TestScope.host(presence: HostPresence?): FormationHost {
    val clock = Clock { testScheduler.currentTime }
    return FormationHost(FormationInfo("session-1", "K7QX", "Aaron", opportunity), TapChallenge, backgroundScope, clock,
      Random(1), HostTiming(briefingMs = 5_000, countdownMs = 1_000), presence = presence)
  }

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
  fun guestsJoinASeekerThatSignsItsUpdates() = runTest {
    val host = host(presence())
    join(host, "Aaron", verifier = null, local = true)
    val maya = join(host, "Maya", testSeekers)
    runCurrent()
    assertEquals(FormationClient.Status.Joined, maya.status.value)
    assertEquals(listOf("Aaron", "Maya"), maya.snapshot.value?.players?.map { it.name })
  }

  @Test
  fun guestsRefuseAHostWithoutProof() = runTest {
    val maya = join(host(presence = null), "Maya", testSeekers)
    runCurrent()
    assertEquals(FormationClient.Status.Untrusted("This host didn't prove it's a Seeker."), maya.status.value)
    assertNull(maya.me.value)
  }

  @Test
  fun releaseBuildsRefuseTestSeekers() = runTest {
    val release = HostVerifier { session, proof ->
      SeekerPresence.problem(proof, session, SeekerPolicy("xyz.mcxross.formation", emptySet(), allowSimulated = false), 0, emptySet())
    }
    val maya = join(host(presence()), "Maya", release)
    runCurrent()
    assertEquals(FormationClient.Status.Untrusted("This host is a test Seeker from a developer build."), maya.status.value)
  }

  @Test
  fun aCopiedProofIsUselessWithoutTheSeekersKey() = runTest {
    // A phone that relays a real Seeker's proof still has to sign with the key that proof names.
    val relay = host(presence(key = Ed25519KeyPair.generate(), signer = Ed25519KeyPair.generate()))
    val maya = join(relay, "Maya", testSeekers)
    runCurrent()
    assertEquals(FormationClient.Status.Untrusted("The Seeker's updates couldn't be verified."), maya.status.value)
    assertNull(maya.me.value)
  }

  @Test
  fun alteredUpdatesEndTheSession() = runTest {
    val host = host(presence())
    val maya = join(host, "Maya", testSeekers, link = { phone ->
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
    val host = host(presence())
    val maya = join(host, "Maya", testSeekers, link = { phone ->
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
}
