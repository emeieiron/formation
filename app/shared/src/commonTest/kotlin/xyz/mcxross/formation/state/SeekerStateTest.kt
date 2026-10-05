package xyz.mcxross.formation.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.platform.SignedMessage
import xyz.mcxross.formation.platform.WalletAccount
import xyz.mcxross.formation.platform.WalletPort
import xyz.mcxross.formation.platform.WalletResult

class SeekerStateTest {
  private class Store : KeyValueStore {
    val map = mutableMapOf<String, String>()

    override fun get(key: String) = map[key]

    override fun put(key: String, value: String?) {
      if (value == null) map.remove(key) else map[key] = value
    }
  }

  // Signs with the key behind the account it answers with, unless [signer] stands in for it.
  private class Wallet(var answer: WalletResult<WalletAccount>, val signer: Ed25519KeyPair? = null) : WalletPort {
    var connects = 0
    var pending: CompletableDeferred<WalletResult<WalletAccount>>? = null

    override fun installed() = true

    override suspend fun connect(): WalletResult<WalletAccount> {
      connects++
      return pending?.await() ?: answer
    }

    override suspend fun signIn(message: ByteArray): WalletResult<SignedMessage> = connect().map { account ->
      SignedMessage(account.address, (signer ?: keys.getValue(account.address)).sign(message))
    }

    override suspend fun signAll(transactions: List<ByteArray>): WalletResult<List<ByteArray>> =
      WalletResult.NoWallet
  }

  private val seedVault = WalletResult.Ok(WalletAccount(SEEKER, "Seed Vault"))
  private val sgts = mutableMapOf(SEEKER to "Sgt111")
  private val check = SeekerCheck { Result.success(sgts[it]) }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test fun leavingAnInFlightLinkDoesNotStrandTheNextVisit() = runTest {
    val wallet = Wallet(seedVault).apply { pending = CompletableDeferred() }
    val state = SeekerState(Store(), wallet, isSeeker = true, developer = false, check)
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
    val state = SeekerState(store, wallet, isSeeker = true, developer = false, check)
    state.autoVerify()
    assertEquals(0, wallet.connects, "the wallet only opens when the owner chooses to link")
    assertEquals(SeekerStatus.NotLinked, state.status.value)
    assertFailsWith<IllegalStateException> { state.requireHostIdentity() }

    state.link()
    assertEquals(
      SeekerIdentity(SEEKER, "Sgt111", simulated = false),
      state.identity.value,
    )
    assertIs<SeekerStatus.Verified>(state.status.value)
    assertEquals(state.identity.value, state.requireHostIdentity())

    val relaunched = SeekerState(store, wallet, isSeeker = true, developer = false, check)
    relaunched.autoVerify()
    assertEquals(1, wallet.connects, "later launches only re-check the stored address")
    assertIs<SeekerStatus.Verified>(relaunched.status.value)
  }

  @Test
  fun otherPhonesAreNeverAsked() = runTest {
    val wallet = Wallet(seedVault)
    val state = SeekerState(Store(), wallet, isSeeker = false, developer = false, check)
    state.autoVerify()
    assertEquals(0, wallet.connects)
    assertEquals(SeekerStatus.NotASeeker, state.status.value)
    assertFailsWith<IllegalStateException> { state.requireHostIdentity() }
  }

  @Test
  fun aDeclinedApprovalWaitsForTheOwner() = runTest {
    val wallet = Wallet(WalletResult.Failed("declined"))
    val state = SeekerState(Store(), wallet, isSeeker = true, developer = false, check)
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
        Wallet(WalletResult.Ok(WalletAccount(OTHER, null))),
        isSeeker = true,
        developer = false,
        check,
      )
    state.link()
    assertNull(state.identity.value)
    assertEquals(SeekerStatus.NoToken(OTHER), state.status.value)
    assertFailsWith<IllegalStateException> { state.requireHostIdentity() }
  }

  @Test
  fun aWalletMustSignForTheAddressItNames() = runTest {
    // An address that holds a Genesis Token proves nothing unless the wallet can sign for it.
    val state = SeekerState(Store(), Wallet(seedVault, signer = Ed25519KeyPair.generate()), isSeeker = true, developer = false, check)
    state.link()
    assertNull(state.identity.value)
    assertEquals(SeekerStatus.NeedsApproval("The wallet's signature doesn't match its address."), state.status.value)
  }

  @Test
  fun aTokenThatIsGoneUnlinksTheSeeker() = runTest {
    val store = Store()
    SeekerState(store, Wallet(seedVault), isSeeker = true, developer = false, check).link()
    sgts.clear()
    val relaunched = SeekerState(store, Wallet(seedVault), isSeeker = true, developer = false, check)
    relaunched.autoVerify()
    assertNull(relaunched.identity.value)
    assertEquals(SeekerStatus.NoToken(SEEKER), relaunched.status.value)
    assertFailsWith<IllegalStateException> { relaunched.requireHostIdentity() }
  }

  @Test
  fun pretendingIsForDebugBuildsOnly() = runTest {
    val store = Store()
    SeekerState(store, Wallet(seedVault), isSeeker = false, developer = false, check)
      .pretend(true, "Claim111")
    assertNull(store.get("seeker"))

    SeekerState(store, Wallet(seedVault), isSeeker = false, developer = true, check)
      .pretend(true, "Claim111")
    val release = SeekerState(store, Wallet(seedVault), isSeeker = false, developer = false, check)
    assertNull(release.identity.value, "a pretend Seeker never carries into a release build")
  }

  private companion object {
    val keys = listOf(Ed25519KeyPair.generate(), Ed25519KeyPair.generate()).associateBy { Base58.encode(it.publicKey) }
    val SEEKER = keys.keys.first()
    val OTHER = keys.keys.last()
  }
}
