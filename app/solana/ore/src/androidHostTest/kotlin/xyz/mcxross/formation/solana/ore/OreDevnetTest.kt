package xyz.mcxross.formation.solana.ore

import com.solana.publickey.SolanaPublicKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

class OreDevnetTest {
  @Test
  fun readsTheDeployedMiningProgram() = runBlocking<Unit> {
    assumeTrue(System.getProperty("formation.ore.devnet") == "true")
    val http = HttpClient(OkHttp)
    try {
      val client = OreRpc(http)
      assertEquals(150uL, client.verifyDeployment().protocol.roundSlots)
      val board = assertNotNull(client.board())
      assertEquals(board.roundId, assertNotNull(client.round(board.roundId)).id)
      assertNotNull(client.treasury())
      assertNotNull(client.miner(SolanaPublicKey.from("9U7jMaMyBBdxxEp4zA5L8DgovQBPsdx6njY5EMXaxEMy")))
    } finally { http.close() }
  }
}
