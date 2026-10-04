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
import xyz.mcxross.formation.platform.KeyValueStore
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

  private class Wallet(var answer: WalletResult<WalletAccount>) : WalletPort {
    var connects = 0
    var pending: CompletableDeferred<WalletResult<WalletAccount>>? = null

    override fun installed() = true

    override suspend fun connect(): WalletResult<WalletAccount> {
      connects++
      return pending?.await() ?: answer
    }

    override suspend fun signAll(transactions: List<ByteArray>): WalletResult<List<ByteArray>> =
      WalletResult.NoWallet
  }

  private val seedVault = WalletResult.Ok(WalletAccount("SeekerWallet111", "Seed Vault"))
  private val sgts = mutableMapOf("SeekerWallet111" to "Sgt111")
  private val check = SeekerCheck { Result.success(sgts[it]) }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test fun leavingAnInFlightLinkDoesNotStrandTheNextVisit() = runTest {
    val wallet = Wallet(seedVault).apply { pending = CompletableDeferred() }
    val state = SeekerState(Store(), wallet, isSeeker = true, debug = false, check)
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
    val state = SeekerState(store, wallet, isSeeker = true, debug = false, check)
    state.autoVerify()
    assertEquals(0, wallet.connects, "the wallet only opens when the owner chooses to link")
    assertEquals(SeekerStatus.NotLinked, state.status.value)
    assertFailsWith<IllegalStateException> { state.requireHostIdentity() }

    state.link()
    assertEquals(
      SeekerIdentity("SeekerWallet111", "Sgt111", simulated = false),
      state.identity.value,
    )
    assertIs<SeekerStatus.Verified>(state.status.value)
    assertEquals(state.identity.value, state.requireHostIdentity())

    val relaunched = SeekerState(store, wallet, isSeeker = true, debug = false, check)
    relaunched.autoVerify()
    assertEquals(1, wallet.connects, "later launches only re-check the stored address")
    assertIs<SeekerStatus.Verified>(relaunched.status.value)
  }

  @Test
  fun otherPhonesAreNeverAsked() = runTest {
    val wallet = Wallet(seedVault)
    val state = SeekerState(Store(), wallet, isSeeker = false, debug = false, check)
    state.autoVerify()
    assertEquals(0, wallet.connects)
    assertEquals(SeekerStatus.NotASeeker, state.status.value)
    assertFailsWith<IllegalStateException> { state.requireHostIdentity() }
  }

  @Test
  fun aDeclinedApprovalWaitsForTheOwner() = runTest {
    val wallet = Wallet(WalletResult.Failed("declined"))
    val state = SeekerState(Store(), wallet, isSeeker = true, debug = false, check)
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
        Wallet(WalletResult.Ok(WalletAccount("Other111", null))),
        isSeeker = true,
        debug = false,
        check,
      )
    state.link()
    assertNull(state.identity.value)
    assertEquals(SeekerStatus.NoToken("Other111"), state.status.value)
    assertFailsWith<IllegalStateException> { state.requireHostIdentity() }
  }

  @Test
  fun aTokenThatIsGoneUnlinksTheSeeker() = runTest {
    val store = Store()
    SeekerState(store, Wallet(seedVault), isSeeker = true, debug = false, check).link()
    sgts.clear()
    val relaunched = SeekerState(store, Wallet(seedVault), isSeeker = true, debug = false, check)
    relaunched.autoVerify()
    assertNull(relaunched.identity.value)
    assertEquals(SeekerStatus.NoToken("SeekerWallet111"), relaunched.status.value)
    assertFailsWith<IllegalStateException> { relaunched.requireHostIdentity() }
  }

  @Test
  fun pretendingIsForDebugBuildsOnly() = runTest {
    val store = Store()
    SeekerState(store, Wallet(seedVault), isSeeker = false, debug = false, check)
      .pretend(true, "Claim111")
    assertNull(store.get("seeker"))

    SeekerState(store, Wallet(seedVault), isSeeker = false, debug = true, check)
      .pretend(true, "Claim111")
    val release = SeekerState(store, Wallet(seedVault), isSeeker = false, debug = false, check)
    assertNull(release.identity.value, "a pretend Seeker never carries into a release build")
  }
}
