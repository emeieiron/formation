package xyz.mcxross.formation.solana.ore

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import xyz.mcxross.formation.solana.SolanaRpc

class OreMainnetTest {
  @Test
  fun readsPublicRoundResultsWithoutAWallet() =
    runBlocking<Unit> {
      assumeTrue(System.getProperty("formation.ore.mainnet") == "true")
      val http = HttpClient(OkHttp)
      try {
        val reader =
          OreRoundReader(
            SolanaRpc(
              http,
              System.getProperty("formation.ore.rpcUrl") ?: OreRoundReader.RPC_URL,
              commitment = "finalized",
            )
          )
        val next = reader.nextRound()
        assertTrue(next > 2)
        val result = reader.result(next - 2)
        assertTrue(result is OreRoundResult.Resolved && result.number in 1..25)
      } finally {
        http.close()
      }
    }
}
