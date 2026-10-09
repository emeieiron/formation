package xyz.mcxross.formation.solana.ore

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import xyz.mcxross.formation.crypto.Base64
import xyz.mcxross.formation.solana.SolanaRpc

class OreRoundReaderTest {
  private fun board(id: Long) =
    ByteArray(40).also {
      it[0] = 105
      id.toULong().littleEndian().copyInto(it, 8)
    }

  private fun round(id: Long, entropy: ByteArray = ByteArray(32).also { it[0] = 16 }) =
    ByteArray(952).also {
      it[0] = 109
      id.toULong().littleEndian().copyInto(it, 8)
      entropy.copyInto(it, 616)
    }

  private fun account(bytes: ByteArray, owner: String = OreRoundReader.PROGRAM_ID.base58()) =
    buildJsonObject {
      put("owner", owner)
      put("lamports", 1)
      putJsonArray("data") {
        add(Base64.encode(bytes))
        add("base64")
      }
    }

  private class Node {
    var genesis = OreRoundReader.MAINNET_GENESIS
    var board =
      ByteArray(40).also {
        it[0] = 105
        41uL.littleEndian().copyInto(it, 8)
      }
    var round: ByteArray? = null
    var owner = OreRoundReader.PROGRAM_ID.base58()
    var fail = false
    val calls = mutableListOf<JsonObject>()
  }

  private fun http(node: Node) =
    HttpClient(
      MockEngine { request ->
        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
        node.calls += body
        if (node.fail) error("RPC unavailable")
        val result =
          when (body.getValue("method").jsonPrimitive.content) {
            "getGenesisHash" -> JsonPrimitive(node.genesis)
            "getMultipleAccounts" -> {
              val params = body.getValue("params").jsonArray
              assertEquals(
                "finalized",
                params[1].jsonObject.getValue("commitment").jsonPrimitive.content,
              )
              buildJsonObject {
                putJsonArray("value") {
                  add(account(node.board, node.owner))
                  if (params[0].jsonArray.size == 2)
                    add(node.round?.let { account(it, node.owner) } ?: JsonNull)
                }
              }
            }
            else -> error("Unexpected RPC method")
          }
        respond(
          buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("result", result)
          }
            .toString(),
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
      }
    )

  @Test
  fun bindsAFutureRoundAndWaitsForTheBoardToAdvance() = runTest {
    val node = Node()
    val http = http(node)
    try {
      val reader = OreRoundReader(SolanaRpc(http, "https://rpc.test", commitment = "finalized"))
      assertEquals(42L, reader.nextRound())
      node.round = round(42)
      node.board = board(42)
      assertEquals(OreRoundResult.Pending, reader.result(42))
      node.board = board(43)
      assertEquals(OreRoundResult.Resolved(17), reader.result(42))
      assertEquals(1, node.calls.count { it["method"]?.jsonPrimitive?.content == "getGenesisHash" })
      assertEquals(
        OreRoundReader.BOARD.base58(),
        node.calls[1].getValue("params").jsonArray[0].jsonArray[0].jsonPrimitive.content,
      )
    } finally {
      http.close()
    }
  }

  @Test
  fun rejectsTheWrongClusterOwnerLayoutAndRoundId() = runTest {
    val node = Node()
    val http = http(node)
    val reader = OreRoundReader(SolanaRpc(http, "https://rpc.test", commitment = "finalized"))
    try {
      node.genesis = OreDeployment.Devnet.genesisHash
      assertFailsWith<IllegalStateException> { reader.nextRound() }
      assertEquals(1, node.calls.size)
      node.genesis = OreRoundReader.MAINNET_GENESIS
      node.owner = "11111111111111111111111111111111"
      assertFailsWith<IllegalStateException> { reader.nextRound() }
      node.owner = OreRoundReader.PROGRAM_ID.base58()
      node.board = ByteArray(48)
      assertFails { reader.nextRound() }
      node.board = board(43)
      node.round = round(41)
      assertFailsWith<IllegalStateException> { reader.result(42) }
      node.round = round(42).also { it[0] = 103 }
      assertFails { reader.result(42) }
    } finally {
      http.close()
    }
  }

  @Test
  fun absentAndInvalidEntropyResultsAreNotLosses() = runTest {
    val node = Node().also { it.board = board(43) }
    val http = http(node)
    val reader = OreRoundReader(SolanaRpc(http, "https://rpc.test", commitment = "finalized"))
    try {
      assertEquals(OreRoundResult.Unavailable, reader.result(42))
      node.round = round(42, ByteArray(32))
      assertEquals(OreRoundResult.Unavailable, reader.result(42))
      node.round = round(42, ByteArray(32) { -1 })
      assertEquals(OreRoundResult.Unavailable, reader.result(42))
      node.round = round(42)
      node.fail = true
      assertFails { reader.result(42) }
      node.fail = false
      assertEquals(OreRoundResult.Resolved(17), reader.result(42))
    } finally {
      http.close()
    }
  }

  @Test
  fun usesUnsignedLittleEndianEntropyAndAllFourLimbs() {
    assertNull(OreRoundReader.winningNumber(ByteArray(32)))
    assertNull(OreRoundReader.winningNumber(ByteArray(32) { -1 }))
    assertEquals(9, OreRoundReader.winningNumber(ByteArray(32).also { it[7] = -128 }))
    assertEquals(
      17,
      OreRoundReader.winningNumber(
        ByteArray(32).also {
          it[0] = 16
          it[8] = 1
          it[16] = 2
          it[24] = 3
        }
      ),
    )
    assertEquals(25, OreRoundReader.winningNumber(ByteArray(32).also { it[0] = 24 }))
    assertEquals(1, OreRoundReader.winningNumber(ByteArray(32).also { it[0] = 25 }))
  }
}
