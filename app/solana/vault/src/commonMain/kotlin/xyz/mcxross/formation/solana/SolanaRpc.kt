package xyz.mcxross.formation.solana

import com.solana.publickey.SolanaPublicKey
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Base64

// [url] is the preferred endpoint, such as a keyed provider; [fallback] takes over while it is rate limited,
// out of quota, refusing its key or unreachable, and the preferred one is retried after [cooldown].
class SolanaRpc(
  private val http: HttpClient,
  private val url: String,
  private val commitment: String = "confirmed",
  private val callTimeout: Duration = 20.seconds,
  private val fallback: String? = null,
  private val cooldown: Duration = 10.minutes,
) {
  private var preferredDownSince: TimeSource.Monotonic.ValueTimeMark? = null

  class Account(val owner: SolanaPublicKey, val lamports: Long, val data: ByteArray)

  sealed interface Filter {
    data class DataSize(val size: Int) : Filter

    class Memcmp(val offset: Int, val bytes: ByteArray) : Filter
  }

  data class Blockhash(val value: String, val lastValidBlockHeight: Long)

  suspend fun latestBlockhash(): String = latestBlockhashInfo().value

  suspend fun latestBlockhashInfo(): Blockhash =
    call("getLatestBlockhash", buildJsonArray { add(options()) })
      .jsonObject
      .getValue("value")
      .jsonObject
      .let { value -> Blockhash(
        value.getValue("blockhash").jsonPrimitive.content,
        value.getValue("lastValidBlockHeight").jsonPrimitive.long,
      ) }

  suspend fun blockHeight(): Long =
    call("getBlockHeight", buildJsonArray { add(options()) }).jsonPrimitive.long

  suspend fun genesisHash(): String =
    call("getGenesisHash", buildJsonArray {}).jsonPrimitive.content

  suspend fun slot(): Long =
    call("getSlot", buildJsonArray { add(options()) }).jsonPrimitive.long

  suspend fun chainTimeMillis(): Long? {
    val slot = slot()
    val time = call("getBlockTime", buildJsonArray { add(slot) })
    return if (time == JsonNull) null else time.jsonPrimitive.long * 1_000
  }

  suspend fun account(key: SolanaPublicKey): Account? = multipleAccounts(listOf(key)).single()

  suspend fun multipleAccounts(keys: List<SolanaPublicKey>): List<Account?> =
    keys.chunked(100).flatMap { chunk ->
      val params = buildJsonArray {
        add(buildJsonArray { chunk.forEach { add(it.base58()) } })
        add(options(base64 = true))
      }
      call("getMultipleAccounts", params).jsonObject.getValue("value").jsonArray.map {
        it.toAccount()
      }
    }

  suspend fun programAccounts(
    program: SolanaPublicKey,
    filters: List<Filter>,
  ): List<Pair<SolanaPublicKey, ByteArray>> {
    val params = buildJsonArray {
      add(program.base58())
      add(
        buildJsonObject {
          put("commitment", commitment)
          put("encoding", "base64")
          putJsonArray("filters") {
            filters.forEach { f ->
              add(
                when (f) {
                  is Filter.DataSize -> buildJsonObject { put("dataSize", f.size) }
                  is Filter.Memcmp ->
                    buildJsonObject {
                      putJsonObject("memcmp") {
                        put("offset", f.offset)
                        put("bytes", Base58.encode(f.bytes))
                      }
                    }
                }
              )
            }
          }
        }
      )
    }
    return call("getProgramAccounts", params).jsonArray.map { it.keyed() }
  }

  suspend fun tokenAccountsByOwner(
    owner: SolanaPublicKey,
    tokenProgram: SolanaPublicKey,
  ): List<Pair<SolanaPublicKey, ByteArray>> {
    val params = buildJsonArray {
      add(owner.base58())
      add(buildJsonObject { put("programId", tokenProgram.base58()) })
      add(options(base64 = true))
    }
    return call("getTokenAccountsByOwner", params).jsonObject.getValue("value").jsonArray.map {
      it.keyed()
    }
  }

  suspend fun sendTransaction(transaction: ByteArray): String {
    val params = buildJsonArray {
      add(Base64.encode(transaction))
      add(
        buildJsonObject {
          put("encoding", "base64")
          put("preflightCommitment", commitment)
        }
      )
    }
    return call("sendTransaction", params).jsonPrimitive.content
  }

  suspend fun signatureStatus(signature: String, history: Boolean = false): SignatureStatus? {
    val params = buildJsonArray {
      add(buildJsonArray { add(signature) })
      add(buildJsonObject { put("searchTransactionHistory", history) })
    }
    val status = call("getSignatureStatuses", params).jsonObject.getValue("value").jsonArray.first()
    if (status is JsonNull) return null
    val o = status.jsonObject
    return SignatureStatus(
      o["confirmationStatus"]?.jsonPrimitive?.contentOrNull,
      o["err"]?.takeUnless { it is JsonNull },
    )
  }

  suspend fun confirm(
    signature: String,
    timeout: Duration = 60.seconds,
    poll: Duration = 600.milliseconds,
  ) {
    val start = TimeSource.Monotonic.markNow()
    while (start.elapsedNow() < timeout) {
      val status = signatureStatus(signature)
      if (status != null) {
        status.error?.let { throw RpcException(-1, "The transaction failed", err = it) }
        if (status.reached(commitment)) return
      }
      delay(poll)
    }
    throw RpcException(-1, "The network didn't confirm the transaction in time")
  }

  class SignatureStatus(val confirmation: String?, val error: JsonElement?) {
    fun reached(commitment: String): Boolean =
      when (commitment) {
        "processed" -> confirmation != null
        "confirmed" -> confirmation == "confirmed" || confirmation == "finalized"
        else -> confirmation == "finalized"
      }
  }

  private fun options(base64: Boolean = false) = buildJsonObject {
    put("commitment", commitment)
    if (base64) put("encoding", "base64")
  }

  private suspend fun call(method: String, params: JsonArray): JsonElement {
    val body = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", 1)
      put("method", method)
      put("params", params)
    }
    val preferredUp = preferredDownSince?.let { it.elapsedNow() >= cooldown } ?: true
    val backup = fallback
    val response =
      if (backup == null) post(url, body)
      else if (!preferredUp) post(backup, body)
      else post(url, body).takeIf { it != null && !it.refused() }
        ?.also { preferredDownSince = null }
        ?: run {
          preferredDownSince = TimeSource.Monotonic.markNow()
          post(backup, body)
        }
    val json = response?.json
      ?: throw RpcException(-1, if (response == null) "No connection to Solana" else "The Solana node sent something unexpected")
    json["error"]
      ?.takeUnless { it is JsonNull }
      ?.jsonObject
      ?.let { e ->
        val data = e["data"]?.takeUnless { it is JsonNull } as? JsonObject
        throw RpcException(
          e["code"]?.jsonPrimitive?.intOrNull ?: -1,
          e["message"]?.jsonPrimitive?.contentOrNull ?: "Solana request failed",
          err = data?.get("err")?.takeUnless { it is JsonNull },
          logs = (data?.get("logs") as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
        )
      }
    return json["result"] ?: JsonNull
  }

  private class Reply(val status: HttpStatusCode, val json: JsonObject?) {
    // A keyed provider says no to every request this way while its quota is spent or its key is revoked.
    fun refused(): Boolean {
      if (status == HttpStatusCode.TooManyRequests || status == HttpStatusCode.Unauthorized ||
        status == HttpStatusCode.Forbidden || status.value >= 500 || json == null) return true
      val error = json["error"] as? JsonObject ?: return false
      val code = error["code"]?.jsonPrimitive?.intOrNull
      val message = error["message"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
      return code == -32429 || code == 429 || code == 401 || code == 403 ||
        listOf("rate limit", "api key", "credits", "quota", "unauthorized").any { it in message }
    }
  }

  // Offline, a request can hang for minutes; failing fast lets callers keep the work for later.
  private suspend fun post(endpoint: String, body: JsonObject): Reply? =
    withContext(Dispatchers.Default) {
      withTimeoutOrNull(callTimeout) {
        runCatching {
          val response = http.post(endpoint) { setBody(TextContent(body.toString(), ContentType.Application.Json)) }
          val text = response.bodyAsText()
          Reply(response.status, runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull())
        }.getOrNull()
      }
    }

  private fun JsonElement.toAccount(): Account? {
    if (this is JsonNull) return null
    val o = jsonObject
    val data = o.getValue("data").jsonArray[0].jsonPrimitive.content
    return Account(
      SolanaPublicKey.from(o.getValue("owner").jsonPrimitive.content),
      o.getValue("lamports").jsonPrimitive.long,
      Base64.decode(data),
    )
  }

  private fun JsonElement.keyed(): Pair<SolanaPublicKey, ByteArray> {
    val o = jsonObject
    val account = o.getValue("account").toAccount() ?: error("Missing account")
    return SolanaPublicKey.from(o.getValue("pubkey").jsonPrimitive.content) to account.data
  }
}

class RpcException(
  val code: Int,
  message: String,
  val err: JsonElement? = null,
  val logs: List<String> = emptyList(),
) : Exception(message) {
  val vaultError: VaultError?
    get() = customCode()?.let(VaultError::of)

  private fun customCode(): Int? {
    val instructionError =
      (err as? JsonObject)?.get("InstructionError") as? JsonArray ?: return null
    val detail = instructionError.getOrNull(1) as? JsonObject ?: return null
    return detail["Custom"]?.jsonPrimitive?.int
  }

  fun describe(): String =
    vaultError?.message
      ?: when {
        logs.any { "insufficient lamports" in it } ||
          message?.contains("insufficient funds", ignoreCase = true) == true ->
          "The wallet needs a little SOL to pay the network fee"
        message?.contains("Blockhash not found", ignoreCase = true) == true ->
          "The transaction took too long. Try again."
        else -> message ?: "Solana request failed"
      }
}
