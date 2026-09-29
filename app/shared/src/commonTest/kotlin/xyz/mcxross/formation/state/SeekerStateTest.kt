package xyz.mcxross.formation.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
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

    override fun installed() = true

    override suspend fun connect(): WalletResult<WalletAccount> = answer.also { connects++ }

    override suspend fun signAll(transactions: List<ByteArray>): WalletResult<List<ByteArray>> =
      WalletResult.NoWallet
  }

  private val seedVault = WalletResult.Ok(WalletAccount("SeekerWallet111", "Seed Vault"))
  private val sgts = mutableMapOf("SeekerWallet111" to "Sgt111")
  private val check = SeekerCheck { Result.success(sgts[it]) }

  @Test
  fun aSeekerVerifiesItselfOnceWithoutAButton() = runTest {
    val store = Store()
    val wallet = Wallet(seedVault)
    val state = SeekerState(store, wallet, isSeeker = true, debug = false, check)
    state.autoVerify()
    assertEquals(
      SeekerIdentity("SeekerWallet111", "Sgt111", simulated = false),
      state.identity.value,
    )
    assertIs<SeekerStatus.Verified>(state.status.value)

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
  }

  @Test
  fun aDeclinedApprovalAsksOnceThenWaitsForRetry() = runTest {
    val wallet = Wallet(WalletResult.Failed("declined"))
    val state = SeekerState(Store(), wallet, isSeeker = true, debug = false, check)
    state.autoVerify()
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
    state.autoVerify()
    assertNull(state.identity.value)
    assertEquals(SeekerStatus.NoToken("Other111"), state.status.value)
  }

  @Test
  fun aTokenThatIsGoneUnlinksTheSeeker() = runTest {
    val store = Store()
    SeekerState(store, Wallet(seedVault), isSeeker = true, debug = false, check).autoVerify()
    sgts.clear()
    val relaunched = SeekerState(store, Wallet(seedVault), isSeeker = true, debug = false, check)
    relaunched.autoVerify()
    assertNull(relaunched.identity.value)
    assertEquals(SeekerStatus.NoToken("SeekerWallet111"), relaunched.status.value)
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
