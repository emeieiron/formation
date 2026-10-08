package xyz.mcxross.formation.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.model.Budget
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.model.OpportunityId
import xyz.mcxross.formation.model.Skr
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.platform.SecretStore
import xyz.mcxross.formation.platform.SignedMessage
import xyz.mcxross.formation.platform.WalletAccount
import xyz.mcxross.formation.platform.WalletPort
import xyz.mcxross.formation.platform.WalletResult
import xyz.mcxross.formation.session.HostChecks

class SeekerStateTest {
  private class Store : KeyValueStore {
    val map = mutableMapOf<String, String>()

    override fun get(key: String) = map[key]

    override fun put(key: String, value: String?) {
      if (value == null) map.remove(key) else map[key] = value
    }
  }

  // Signs with the key behind the account it answers with, unless [signer] stands in for it.
  private class Wallet(
    var answer: WalletResult<WalletAccount>,
    val signer: Ed25519KeyPair? = null,
  ) : WalletPort {
    var connects = 0
    var pending: CompletableDeferred<WalletResult<WalletAccount>>? = null

    override fun installed() = true

    override suspend fun connect(): WalletResult<WalletAccount> {
      connects++
      return pending?.await() ?: answer
    }

    override suspend fun signIn(message: ByteArray): WalletResult<SignedMessage> =
      connect().map { account ->
        SignedMessage(account.address, (signer ?: keys.getValue(account.address)).sign(message))
      }

    override suspend fun signAll(transactions: List<ByteArray>): WalletResult<List<ByteArray>> =
      WalletResult.NoWallet
  }

  private class Secrets : SecretStore {
    val map = mutableMapOf<String, ByteArray>()

    override fun get(name: String) = map[name]

    override fun put(name: String, value: ByteArray) {
      map[name] = value
    }

    override fun remove(name: String) {
      map.remove(name)
    }
  }

  private val secrets = Secrets()
  private val seedVault = WalletResult.Ok(WalletAccount(SEEKER, "Seed Vault"))
  private val sgts = mutableMapOf(SEEKER to "Sgt111")
  private val check = SeekerCheck { Result.success(sgts[it]) }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun leavingAnInFlightLinkDoesNotStrandTheNextVisit() = runTest {
    val wallet = Wallet(seedVault).apply { pending = CompletableDeferred() }
    val state = SeekerState(Store(), secrets, wallet, isSeeker = true, NETWORK, check)
    val request = launch { state.link() }
    runCurrent()
    assertEquals(SeekerStatus.Checking, state.status.value)
    request.cancelAndJoin()
    assertEquals(SeekerStatus.NotLinked, state.status.value)
    assertFailsWith<IllegalStateException> { state.requireHostIdentity() }
    wallet.pending = null
    state.link()
    assertIs<SeekerStatus.Verified>(state.status.value)
    assertEquals(state.identity.value, state.requireHostIdentity())
  }

  @Test
  fun aSeekerIsLinkedOnceThenRecheckedQuietly() = runTest {
    val store = Store()
    val wallet = Wallet(seedVault)
    val state = SeekerState(store, secrets, wallet, isSeeker = true, NETWORK, check)
    state.autoVerify()
    assertEquals(0, wallet.connects, "the wallet only opens when the owner chooses to link")
    assertEquals(SeekerStatus.NotLinked, state.status.value)
    assertFailsWith<IllegalStateException> { state.requireHostIdentity() }

    state.link()
    assertEquals(
      SeekerIdentity(SEEKER, "Sgt111", "", ""),
      state.identity.value?.copy(authorization = "", signature = ""),
    )
    assertIs<SeekerStatus.Verified>(state.status.value)
    assertEquals(state.identity.value, state.requireHostIdentity())

    val relaunched = SeekerState(store, secrets, wallet, isSeeker = true, NETWORK, check)
    relaunched.autoVerify()
    assertEquals(1, wallet.connects, "later launches only re-check the stored address")
    assertIs<SeekerStatus.Verified>(relaunched.status.value)
  }

  @Test
  fun otherPhonesAreNeverAsked() = runTest {
    val wallet = Wallet(seedVault)
    val state = SeekerState(Store(), secrets, wallet, isSeeker = false, NETWORK, check)
    state.autoVerify()
    assertEquals(0, wallet.connects)
    assertEquals(SeekerStatus.NotASeeker, state.status.value)
    assertFailsWith<IllegalStateException> { state.requireHostIdentity() }
  }

  @Test
  fun aDeclinedApprovalWaitsForTheOwner() = runTest {
    val wallet = Wallet(WalletResult.Failed("declined"))
    val state = SeekerState(Store(), secrets, wallet, isSeeker = true, NETWORK, check)
    state.link()
    state.autoVerify()
    assertEquals(1, wallet.connects)
    assertIs<SeekerStatus.NeedsApproval>(state.status.value)

    wallet.answer = seedVault
    state.link()
    assertIs<SeekerStatus.Verified>(state.status.value)
  }

  @Test
  fun aWalletWithoutTheTokenIsNotASeeker() = runTest {
    val state =
      SeekerState(
        Store(),
        secrets,
        Wallet(WalletResult.Ok(WalletAccount(OTHER, null))),
        isSeeker = true,
        NETWORK,
        check,
      )
    state.link()
    assertNull(state.identity.value)
    assertEquals(SeekerStatus.NoToken(OTHER), state.status.value)
    assertFailsWith<IllegalStateException> { state.requireHostIdentity() }
  }

  @Test
  fun linkingAuthorizesThisPhoneToHost() = runTest {
    val state = SeekerState(Store(), secrets, Wallet(seedVault), isSeeker = false, NETWORK, check)
    state.link()
    val opportunity =
      Opportunity(
        Budget(
          OpportunityId("So11111111111111111111111111111111111111112"),
          "11111111111111111111111111111111",
          "sgt",
          Skr.of(600),
          3,
          31,
          Long.MAX_VALUE,
          "Test",
        ),
        ChallengeId("tap"),
        3,
      )
    // Any phone can host once its wallet authorizes it, and every guest can check that.
    val proof = state.credentials("session-1", opportunity).proof
    assertNull(HostChecks.problem(proof, "session-1", NETWORK))

    state.forget()
    assertNull(
      secrets.get("host-key"),
      "an unlinked phone keeps no key that could sign for the wallet",
    )
  }

  @Test
  fun aWalletMustSignForTheAddressItNames() = runTest {
    // An address that holds a Genesis Token proves nothing unless the wallet can sign for it.
    val state =
      SeekerState(
        Store(),
        secrets,
        Wallet(seedVault, signer = Ed25519KeyPair.generate()),
        isSeeker = true,
        NETWORK,
        check,
      )
    state.link()
    assertNull(state.identity.value)
    assertEquals(
      SeekerStatus.NeedsApproval("The wallet's signature doesn't match its address."),
      state.status.value,
    )
  }

  @Test
  fun aTokenThatIsGoneUnlinksTheSeeker() = runTest {
    val store = Store()
    SeekerState(store, secrets, Wallet(seedVault), isSeeker = true, NETWORK, check).link()
    sgts.clear()
    val relaunched = SeekerState(store, secrets, Wallet(seedVault), isSeeker = true, NETWORK, check)
    relaunched.autoVerify()
    assertNull(relaunched.identity.value)
    assertEquals(SeekerStatus.NoToken(SEEKER), relaunched.status.value)
    assertFailsWith<IllegalStateException> { relaunched.requireHostIdentity() }
  }

  @Test
  fun aTestSeekerIsFundedThenLinksThisPhonesOwnWallet() = runTest {
    val funded = mutableListOf<String>()
    val wallet = Wallet(seedVault)
    val state =
      SeekerState(
        Store(),
        secrets,
        wallet,
        isSeeker = true,
        NETWORK,
        check,
        faucet = { address ->
          funded += address.base58()
          sgts[address.base58()] = "TestSgt"
          Result.success(Unit)
        },
      )
    state.becomeHost()
    val identity = assertIs<SeekerStatus.Verified>(state.status.value).identity
    assertEquals(0, wallet.connects, "a test Seeker never opens a wallet app")
    assertEquals(listOf(identity.wallet), funded)
    assertEquals(Base58.encode(state.testWallet()!!.publicKey), identity.wallet)
    assertEquals("TestSgt", identity.sgt)
    assertEquals(true, identity.test)
    // Everything after linking is the same as for a real wallet: guests check the same proof.
    val opportunity =
      Opportunity(
        Budget(
          OpportunityId("So11111111111111111111111111111111111111112"),
          "11111111111111111111111111111111",
          "sgt",
          Skr.of(600),
          3,
          31,
          Long.MAX_VALUE,
          "Test",
        ),
        ChallengeId("tap"),
        3,
      )
    assertNull(
      HostChecks.problem(state.credentials("session-1", opportunity).proof, "session-1", NETWORK)
    )

    state.forget()
    state.becomeTestSeeker()
    assertEquals(identity.wallet, state.identity.value?.wallet, "the phone keeps one test wallet")
  }

  @Test
  fun aFaucetThatCantHelpIsExplained() = runTest {
    val state =
      SeekerState(
        Store(),
        secrets,
        Wallet(seedVault),
        isSeeker = false,
        NETWORK,
        check,
        faucet = { Result.failure(IllegalStateException("Too many requests today")) },
      )
    state.becomeTestSeeker()
    assertNull(state.identity.value)
    assertEquals(
      SeekerStatus.NeedsApproval("Couldn't get a test token: Too many requests today"),
      state.status.value,
    )
  }

  @Test
  fun withoutAFaucetHostingMeansLinkingAWallet() = runTest {
    val wallet = Wallet(seedVault)
    val state = SeekerState(Store(), secrets, wallet, isSeeker = true, NETWORK, check)
    assertEquals(false, state.testSeekers)
    state.becomeHost()
    assertEquals(1, wallet.connects)
    assertEquals(false, state.identity.value?.test)
  }

  private companion object {
    val keys =
      listOf(Ed25519KeyPair.generate(), Ed25519KeyPair.generate()).associateBy {
        Base58.encode(it.publicKey)
      }
    val SEEKER = keys.keys.first()
    const val NETWORK = "devnet"
    val OTHER = keys.keys.last()
  }
}
