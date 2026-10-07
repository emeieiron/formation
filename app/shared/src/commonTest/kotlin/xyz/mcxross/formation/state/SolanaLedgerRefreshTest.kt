package xyz.mcxross.formation.state

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.platform.WalletPort
import xyz.mcxross.formation.platform.WalletResult
import xyz.mcxross.formation.solana.SolanaRpc

class SolanaLedgerRefreshTest {
  private val wallet = object : WalletPort {
    override fun installed() = false
    override suspend fun connect() = WalletResult.NoWallet
    override suspend fun signIn(message: ByteArray) = WalletResult.NoWallet
    override suspend fun signAll(transactions: List<ByteArray>) = WalletResult.NoWallet
  }
  private val store = object : KeyValueStore {
    override fun get(key: String): String? = null
    override fun put(key: String, value: String?) = Unit
  }
  private val seeker = SeekerIdentity("11111111111111111111111111111111", "11111111111111111111111111111111", "", "")
  private fun ledger(http: HttpClient) = SolanaLedger(
    SolanaRpc(http, "https://rpc.test"), wallet, { error("Refresh must not sign") }, { null }, "devnet", store,
  )

  @Test
  fun leavingHomeDuringRefreshDoesNotReportNavigationCancellationAsANetworkFailure() = runTest {
    val started = CompletableDeferred<Unit>()
    val http = HttpClient(MockEngine {
      started.complete(Unit)
      awaitCancellation()
    })
    try {
      val ledger = ledger(http)
      val refresh = launch { ledger.refresh(seeker) }
      started.await()
      refresh.cancelAndJoin()
      assertNull(ledger.problem.value)
    } finally { http.close() }
  }

  @Test
  fun aRealNodeFailureStillAppearsInTheRewardStatus() = runTest {
    val http = HttpClient(MockEngine {
      respond("""{"jsonrpc":"2.0","id":1,"error":{"code":-1,"message":"Node unavailable"}}""",
        headers = headersOf(HttpHeaders.ContentType, "application/json"))
    })
    try {
      val ledger = ledger(http)
      ledger.refresh(seeker)
      assertEquals("Couldn't reach Solana: Node unavailable", ledger.problem.value)
    } finally { http.close() }
  }
}
