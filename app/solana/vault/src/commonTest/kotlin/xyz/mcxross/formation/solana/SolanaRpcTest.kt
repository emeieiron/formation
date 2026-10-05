package xyz.mcxross.formation.solana

import com.solana.publickey.SolanaPublicKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mcxross.formation.crypto.Base64

class SolanaRpcTest {
  private class Node(private val answers: Map<String, List<String>>) {
    val calls = mutableListOf<Pair<String, String>>()
    private val served = mutableMapOf<String, Int>()

    val http =
      HttpClient(
        MockEngine { request ->
          val body = (request.body as TextContent).text
          val method =
            Json.parseToJsonElement(body).jsonObject.getValue("method").jsonPrimitive.content
          calls += method to body
          val options = answers.getValue(method)
          val answer = options[minOf(served.getOrElse(method) { 0 }, options.lastIndex)]
          served[method] = served.getOrElse(method) { 0 } + 1
          respond(
            """{"jsonrpc":"2.0","id":1,$answer}""",
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
      )

    fun rpc() = SolanaRpc(http, "https://rpc.test")
  }

  private fun result(json: String) = """"result":$json"""

  private fun account(owner: SolanaPublicKey, data: ByteArray) =
    """{"lamports":2039280,"owner":"${owner.base58()}","data":["${Base64.encode(data)}","base64"],"executable":false,"rentEpoch":0}"""

  @Test
  fun findsEntriesForAGenesisToken() = runTest {
    val sgt = SolanaPublicKey(ByteArray(32) { 4 })
    val address = SolanaPublicKey(ByteArray(32) { 6 })
    val data = entryBytes(sgt)
    val node =
      Node(
        mapOf(
          "getProgramAccounts" to
            listOf(
              result(
                """[{"pubkey":"${address.base58()}","account":${account(FormationVault.PROGRAM_ID, data)}}]"""
              )
            )
        )
      )

    val found =
      node
        .rpc()
        .programAccounts(
          FormationVault.PROGRAM_ID,
          listOf(
            SolanaRpc.Filter.Memcmp(0, VaultEntry.DISCRIMINATOR),
            SolanaRpc.Filter.Memcmp(VaultEntry.SGT_OFFSET, sgt.bytes),
          ),
        )
    val entry = VaultEntry.decode(found.single().first, found.single().second)
    assertEquals(address, entry.address)
    assertEquals(sgt, entry.sgt)
    assertEquals(300_000_000uL, entry.budget)
    assertEquals(EntryState.REGISTERED, entry.state)

    val filters =
      Json.parseToJsonElement(node.calls.single().second)
        .jsonObject
        .getValue("params")
        .jsonArray[1]
        .jsonObject
        .getValue("filters")
        .jsonArray
    assertEquals(
      sgt.base58(),
      filters[1].jsonObject.getValue("memcmp").jsonObject.getValue("bytes").jsonPrimitive.content,
    )
  }

  @Test
  fun failedSimulationsSayWhy() = runTest {
    val error =
      """"error":{"code":-32002,"message":"Transaction simulation failed: Error processing Instruction 0: custom program error: 0x1789",
        "data":{"err":{"InstructionError":[0,{"Custom":6025}]},"logs":["Program log: AnchorError occurred."]}}"""
    val node = Node(mapOf("sendTransaction" to listOf(error)))
    val e = assertFailsWith<RpcException> { node.rpc().sendTransaction(ByteArray(10)) }
    assertEquals(VaultError.NOT_IN_ROSTER, e.vaultError)
    assertEquals(VaultError.NOT_IN_ROSTER.message, e.describe())

    val broke =
      Node(
        mapOf(
          "sendTransaction" to
            listOf(
              """"error":{"code":-32002,"message":"Transaction simulation failed: Attempt to debit an account but found no record of a prior credit."}"""
            )
        )
      )
    val e2 = assertFailsWith<RpcException> { broke.rpc().sendTransaction(ByteArray(10)) }
    assertNull(e2.vaultError)
  }

  @Test
  fun confirmationWaitsForTheCommitment() = runTest {
    val statuses =
      listOf(
        result("""{"context":{"slot":1},"value":[null]}"""),
        result(
          """{"context":{"slot":2},"value":[{"slot":2,"confirmations":0,"err":null,"confirmationStatus":"processed"}]}"""
        ),
        result(
          """{"context":{"slot":3},"value":[{"slot":2,"confirmations":1,"err":null,"confirmationStatus":"confirmed"}]}"""
        ),
      )
    val node = Node(mapOf("getSignatureStatuses" to statuses))
    node.rpc().confirm("sig", poll = 1.milliseconds)
    assertEquals(3, node.calls.size)

    val failed =
      Node(
        mapOf(
          "getSignatureStatuses" to
            listOf(
              result(
                """{"context":{"slot":3},"value":[{"slot":2,"err":{"InstructionError":[0,{"Custom":6026}]},"confirmationStatus":"confirmed"}]}"""
              )
            )
        )
      )
    val e = assertFailsWith<RpcException> { failed.rpc().confirm("sig", poll = 1.milliseconds) }
    assertEquals(VaultError.ALREADY_CLAIMED, e.vaultError)
  }

  @Test
  fun findsTheSeekerGenesisTokenInAWallet() = runTest {
    val stray =
      TokenAccount.SIZE.let { ByteArray(it) }.also { Mainnet.sgtAccountData.copyInto(it, 0, 0, 64) }
    val node =
      Node(
        mapOf(
          "getTokenAccountsByOwner" to
            listOf(
              result(
                """{"context":{"slot":1},"value":[
                  {"pubkey":"${SolanaPublicKey(ByteArray(32) { 3 }).base58()}","account":${account(SeekerGenesis.TOKEN_2022, stray)}},
                  {"pubkey":"${Mainnet.sgtAccount.base58()}","account":${account(SeekerGenesis.TOKEN_2022, Mainnet.sgtAccountData)}}]}"""
              )
            ),
          "getMultipleAccounts" to
            listOf(
              result(
                """{"context":{"slot":1},"value":[${account(SeekerGenesis.TOKEN_2022, Mainnet.sgtMint)}]}"""
              )
            ),
        )
      )
    val found = assertNotNull(SgtFinder(node.rpc(), SeekerGenesis.MAINNET_GROUP).find(Mainnet.holder))
    assertEquals(Mainnet.sgt, found.mint)
    assertEquals(Mainnet.sgtAccount, found.account)
    assertNull(SgtFinder(node.rpc(), group = SolanaPublicKey(ByteArray(32))).find(Mainnet.holder))
  }

  @Test
  fun anUnreachableNodeFailsFast() = runTest {
    val stalled = HttpClient(MockEngine { kotlinx.coroutines.awaitCancellation() })
    val e =
      assertFailsWith<RpcException> {
        SolanaRpc(stalled, "https://rpc.test", callTimeout = 200.milliseconds).latestBlockhash()
      }
    assertEquals("No connection to Solana", e.message)
  }

  @Test
  fun readsBlockhashesAndAccounts() = runTest {
    val node =
      Node(
        mapOf(
          "getLatestBlockhash" to
            listOf(
              result(
                """{"context":{"slot":1},"value":{"blockhash":"EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N","lastValidBlockHeight":9}}"""
              )
            ),
          "getMultipleAccounts" to listOf(result("""{"context":{"slot":1},"value":[null]}""")),
        )
      )
    assertEquals("EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N", node.rpc().latestBlockhash())
    assertNull(node.rpc().account(Mainnet.sgt))
  }

  private fun entryBytes(sgt: SolanaPublicKey): ByteArray {
    val key = { b: Int -> SolanaPublicKey(ByteArray(32) { b.toByte() }) }
    val bytes =
      BorshWriter()
        .bytes(VaultEntry.DISCRIMINATOR)
        .key(key(1))
        .key(sgt)
        .u8(0)
        .u32(3)
        .key(key(2))
        .key(key(2))
        .u8(0)
        .u64(300_000_000uL)
        .bytes(ByteArray(32))
        .u8(0)
        .u64(0uL)
        .u64(0uL)
        .u64(0uL)
        .bytes(ByteArray(32))
        .i64(0)
        .i64(1_800_000_000)
        .u8(254)
        .toByteArray()
    assertContentEquals(sgt.bytes, bytes.copyOfRange(VaultEntry.SGT_OFFSET, VaultEntry.SGT_OFFSET + 32))
    return bytes
  }
}
