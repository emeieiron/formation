package xyz.mcxross.formation.state

import com.solana.publickey.SolanaPublicKey
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Asks the faucet to send a test Seeker's wallet devnet SOL for fees and mint it a test Genesis Token.
class TestFaucet(private val http: HttpClient, private val url: String, private val userAgent: String) {
  suspend fun fund(wallet: SolanaPublicKey): Result<Unit> = runCatching {
    val response = http.post(url) {
      // Cloudflare turns away generic client user agents.
      header("user-agent", userAgent)
      setBody(TextContent(buildJsonObject { put("wallet", wallet.base58()) }.toString(), ContentType.Application.Json))
    }
    if (!response.status.isSuccess()) {
      val reason = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonPrimitive.content }
      error(reason.getOrDefault("The faucet is unavailable"))
    }
  }
}
