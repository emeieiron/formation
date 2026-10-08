package xyz.mcxross.formation.solana.ore

import com.solana.publickey.SolanaPublicKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OreRpcTest {
  private val authority = SolanaPublicKey.from("9U7jMaMyBBdxxEp4zA5L8DgovQBPsdx6njY5EMXaxEMy")
  private val fixtures =
    Json.parseToJsonElement(oreFixtures).jsonObject.getValue("accounts").jsonObject

  @Test
  fun verifiesDevnetAndReadsTypedAccounts() = runTest {
    val requests = mutableListOf<JsonObject>()
    val http =
      node(requests) { method ->
        if (method == "getGenesisHash") JsonPrimitive(OreDeployment.Devnet.genesisHash).toString()
        else if (method == "getSlot") "508044077"
        else
          accountResponse(
            fixtures.getValue(if (requests.size == 2) "config" else "board").toString()
          )
      }
    try {
      val client = OreRpc(http)
      assertEquals(150uL, client.verifyDeployment().protocol.roundSlots)
      assertEquals(508044077, client.rpc.slot())
      assertNotNull(client.board())
      assertEquals(
        listOf("getGenesisHash", "getMultipleAccounts", "getSlot", "getMultipleAccounts"),
        requests.map { it.getValue("method").jsonPrimitive.content },
      )
      assertEquals(
        client.program.config().base58(),
        requests[1].getValue("params").jsonArray[0].jsonArray[0].jsonPrimitive.content,
      )
      assertEquals(
        "confirmed",
        requests[1]
          .getValue("params")
          .jsonArray[1]
          .jsonObject
          .getValue("commitment")
          .jsonPrimitive
          .content,
      )
      assertEquals(
        "base64",
        requests[1]
          .getValue("params")
          .jsonArray[1]
          .jsonObject
          .getValue("encoding")
          .jsonPrimitive
          .content,
      )
    } finally {
      http.close()
    }
  }

  @Test
  fun rejectsOtherClustersBeforeReadingAccounts() = runTest {
    val requests = mutableListOf<JsonObject>()
    val http = node(requests) { "\"mainnet-genesis\"" }
    try {
      assertFailsWith<IllegalStateException> { OreRpc(http).verifyDeployment() }
      assertEquals(1, requests.size)
    } finally {
      http.close()
    }
  }

  @Test
  fun absentMinerAndRoundReturnNull() = runTest {
    val http = node { accountResponse("null") }
    try {
      val client = OreRpc(http)
      assertNull(client.miner(authority))
      assertNull(client.round(99uL))
      assertNull(client.config())
    } finally {
      http.close()
    }
  }

  @Test
  fun rejectsForeignOwnersAndInconsistentIdentities() = runTest {
    val foreign =
      JsonObject(
        fixtures.getValue("board").jsonObject +
          ("owner" to JsonPrimitive("11111111111111111111111111111111"))
      )
    val ownerHttp = node { accountResponse(foreign.toString()) }
    try {
      assertFailsWith<IllegalStateException> { OreRpc(ownerHttp).board() }
    } finally {
      ownerHttp.close()
    }
    val minerHttp = node { accountResponse(fixtures.getValue("miner").toString()) }
    try {
      assertNotNull(OreRpc(minerHttp).miner(authority))
      assertFailsWith<IllegalStateException> {
        OreRpc(minerHttp).miner(SolanaPublicKey(ByteArray(32) { 1 }))
      }
    } finally {
      minerHttp.close()
    }
    val roundHttp = node { accountResponse(fixtures.getValue("round").toString()) }
    try {
      assertNotNull(OreRpc(roundHttp).round(3uL))
      assertFailsWith<IllegalStateException> { OreRpc(roundHttp).round(4uL) }
    } finally {
      roundHttp.close()
    }
  }

  @Test
  fun rejectsUnexpectedEntropyConfiguration() = runTest {
    val program =
      OreProgram(OreDeployment.Devnet.copy(entropyProgramId = SolanaPublicKey(ByteArray(32) { 1 })))
    val http = node { method ->
      if (method == "getGenesisHash") JsonPrimitive(OreDeployment.Devnet.genesisHash).toString()
      else accountResponse(fixtures.getValue("config").toString())
    }
    try {
      assertFailsWith<IllegalStateException> {
        OreRpc(xyz.mcxross.formation.solana.SolanaRpc(http, OreDeployment.Devnet.rpcUrl), program)
          .verifyDeployment()
      }
    } finally {
      http.close()
    }
  }

  private fun accountResponse(account: String) = """{"context":{"slot":1},"value":[$account]}"""

  private fun node(
    requests: MutableList<JsonObject> = mutableListOf(),
    response: (String) -> String,
  ): HttpClient =
    HttpClient(
      MockEngine { request ->
        assertEquals(OreDeployment.Devnet.rpcUrl, request.url.toString())
        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
        requests += body
        val result = response(body.getValue("method").jsonPrimitive.content)
        respond(
          """{"jsonrpc":"2.0","id":1,"result":$result}""",
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
      }
    )
}
