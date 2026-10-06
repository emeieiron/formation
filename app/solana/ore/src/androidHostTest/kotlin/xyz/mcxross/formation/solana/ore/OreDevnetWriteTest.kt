package xyz.mcxross.formation.solana.ore

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.Transaction
import com.solana.transaction.TransactionInstruction
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assume.assumeTrue
import xyz.mcxross.formation.solana.transaction

class OreDevnetWriteTest {
  private val wallet = SolanaPublicKey.from("9U7jMaMyBBdxxEp4zA5L8DgovQBPsdx6njY5EMXaxEMy")

  @Test
  fun deploysCheckpointsAndClaimsOnDevnet() = runBlocking<Unit> {
    assumeTrue(System.getProperty("formation.ore.devnet.write") == "true")
    val key = loadExternalKey()
    val http = HttpClient(OkHttp)
    try {
      val client = OreRpc(http)
      client.verifyDeployment()
      val initialBalance = assertNotNull(client.rpc.account(wallet)).lamports
      require(initialBalance >= 15_000_000) { "The deployment wallet needs at least 0.015 devnet SOL, including reset rent" }
      val board = assertNotNull(client.board())
      check(board.waitingForDeployment) { "An ORE round is already active" }
      val previousMiner = assertNotNull(client.miner(wallet))
      check(previousMiner.roundId == previousMiner.checkpointId) { "Checkpoint the previous round first" }
      check(previousMiner.autoReturn == 1uL) { "Expected the deployment miner to auto-return SOL" }
      val amount = 25_000uL
      val signatures = linkedMapOf<String, String>()

      suspend fun submit(name: String, instruction: TransactionInstruction) {
        val unsigned = transaction(wallet, client.rpc.latestBlockhash(), instruction)
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(key)
        signer.update(unsigned.message.serialize())
        val signed = Transaction(listOf(signer.sign()), unsigned.message)
        val signature = client.rpc.sendTransaction(signed.serialize())
        signatures[name] = signature
        println("ORE devnet $name: $signature")
        client.rpc.confirm(signature)
      }

      submit("deploy", client.program.deploy(wallet, wallet, board.roundId, amount, (0..24).toSet()))
      val deployed = assertNotNull(client.miner(wallet))
      assertEquals(board.roundId, deployed.roundId)
      assertEquals(List(25) { amount }, deployed.deployed)
      assertEquals(List(25) { amount }, assertNotNull(client.round(board.roundId)).deployed)
      val afterDeployBalance = assertNotNull(client.rpc.account(wallet)).lamports
      assertTrue(initialBalance - afterDeployBalance in 625_000..5_000_000)

      withTimeout(5.minutes) {
        while (assertNotNull(client.board()).roundId == board.roundId) delay(5_000)
      }
      val resolved = assertNotNull(client.round(board.roundId))
      assertTrue(resolved.entropy.any { it != 0.toByte() })
      assertTrue(resolved.entropy.any { it != (-1).toByte() })
      val beforeCheckpointBalance = assertNotNull(client.rpc.account(wallet)).lamports
      submit("checkpoint", client.program.checkpoint(wallet, wallet, board.roundId))
      val checkpointed = assertNotNull(client.miner(wallet))
      assertEquals(board.roundId, checkpointed.checkpointId)
      assertEquals(0uL, checkpointed.rewardsSol)
      assertTrue(checkpointed.rewardsOre > previousMiner.rewardsOre)
      val returnedSol = checkpointed.lifetimeRewardsSol - previousMiner.lifetimeRewardsSol
      assertTrue(returnedSol > 0uL)
      val checkpointDelta = assertNotNull(client.rpc.account(wallet)).lamports - beforeCheckpointBalance
      assertEquals(returnedSol.toLong() - 5_000, checkpointDelta)

      val beforeSolClaim = assertNotNull(client.rpc.account(wallet)).lamports
      submit("claimSol", client.program.claimSol(wallet))
      val solClaimed = assertNotNull(client.miner(wallet))
      assertEquals(0uL, solClaimed.rewardsSol)
      assertTrue(solClaimed.lastClaimSolAt > checkpointed.lastClaimSolAt)
      assertEquals(-5_000, assertNotNull(client.rpc.account(wallet)).lamports - beforeSolClaim)

      val tokenAccount = client.program.tokenAccount(wallet)
      val beforeTokens = tokenAmount(assertNotNull(client.rpc.account(tokenAccount)).data)
      val beforeTreasury = assertNotNull(client.treasury())
      val grossOre = solClaimed.refinedOre + solClaimed.rewardsOre
      val fee = if (solClaimed.rewardsOre > 0uL && beforeTreasury.totalUnclaimed > solClaimed.rewardsOre)
        maxOf(1uL, solClaimed.rewardsOre / 10uL) else 0uL
      submit("claimOre", client.program.claimOre(wallet))
      val claimed = assertNotNull(client.miner(wallet))
      assertEquals(0uL, claimed.rewardsOre)
      assertEquals(0uL, claimed.refinedOre)
      assertTrue(claimed.lastClaimOreAt > solClaimed.lastClaimOreAt)
      val receivedOre = tokenAmount(assertNotNull(client.rpc.account(tokenAccount)).data) - beforeTokens
      assertEquals(grossOre - fee, receivedOre)
      assertTrue(receivedOre > 0uL)
      val finalBalance = assertNotNull(client.rpc.account(wallet)).lamports
      assertTrue(initialBalance - finalBalance <= 15_000_000)
      val report = buildJsonObject {
        put("cluster", "devnet")
        put("wallet", wallet.base58())
        put("roundId", board.roundId.toString())
        put("deployedLamports", (25uL * amount).toString())
        put("returnedSolLamports", returnedSol.toString())
        put("claimedOreUnits", receivedOre.toString())
        put("netWalletSpendLamports", initialBalance - finalBalance)
        put("claimSolTransferredLamports", 0)
        put("solAutoReturnedAtCheckpoint", true)
        putJsonObject("signatures") { signatures.forEach { (name, signature) -> put(name, signature) } }
      }
      val reportPath = Path.of(System.getProperty("formation.ore.writeReport"))
      Files.createDirectories(reportPath.parent)
      Files.writeString(reportPath, report.toString())
    } finally { http.close() }
  }

  private fun loadExternalKey(): PrivateKey {
    val repository = Path.of(System.getProperty("formation.repository")).toRealPath()
    val file = Path.of(System.getenv("ORE_DEVNET_PAYER")
      ?: "${System.getProperty("user.home")}/.config/solana/seekers-devnet.json").toRealPath()
    require(!file.startsWith(repository)) { "The deployment key must be outside the repository" }
    return runCatching {
      val values = Json.parseToJsonElement(Files.readString(file)).jsonArray
      require(values.size == 64)
      val bytes = ByteArray(64) { index ->
        val value = values[index].jsonPrimitive.int
        require(value in 0..255)
        value.toByte()
      }
      try {
        require(bytes.copyOfRange(32, 64).contentEquals(wallet.bytes))
        val prefix = byteArrayOf(0x30, 0x2e, 2, 1, 0, 0x30, 5, 6, 3, 0x2b, 0x65, 0x70, 4, 0x22, 4, 0x20)
        val encoded = prefix + bytes.copyOfRange(0, 32)
        try { KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(encoded)) }
        finally { encoded.fill(0) }
      } finally { bytes.fill(0) }
    }.getOrElse { error("Could not load the external deployment wallet") }
  }

  private fun tokenAmount(data: ByteArray): ULong {
    require(data.size == 165) { "Unexpected SPL token account size" }
    return (0 until 8).fold(0uL) { result, index ->
      result or (data[64 + index].toUByte().toULong() shl (index * 8))
    }
  }
}
