package xyz.mcxross.formation.solana.ore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mcxross.formation.crypto.Base64

class OreAccountsTest {
  private val fixtures = Json.parseToJsonElement(oreFixtures).jsonObject

  @Test
  fun decodesPublicDevnetAccounts() {
    val board = OreBoard.decode(data("board"))
    val b = fixtures.getValue("board").jsonObject
    assertEquals(b.u64("round_id"), board.roundId)
    assertEquals(b.u64("start_slot"), board.startSlot)
    assertEquals(ULong.MAX_VALUE, board.endSlot)
    assertTrue(board.waitingForDeployment)
    assertEquals(b.u64("production_cost_ema"), board.productionCostEma)

    val config = OreConfig.decode(data("config"))
    assertEquals("9U7jMaMyBBdxxEp4zA5L8DgovQBPsdx6njY5EMXaxEMy", config.admin.authority.base58())
    assertEquals(config.admin.authority, config.admin.feeCollector)
    assertEquals(100uL, config.admin.feeRate)
    assertEquals(1000uL, config.protocol.feeRate)
    assertEquals(150uL, config.protocol.roundSlots)
    assertEquals(5uL, config.protocol.intermissionSlots)
    assertEquals(OreDeployment.Devnet.entropyProgramId, config.protocol.entropyProgramId)
    assertEquals(
      "6CVbEiJUsrgZ6jK8MHHS6bTSaDx39HN1RaJqVX6Qv35V",
      config.protocol.entropyVar.base58(),
    )

    val miner = OreMiner.decode(data("miner"))
    val m = fixtures.getValue("miner").jsonObject
    assertEquals(m.getValue("authority").jsonPrimitive.content, miner.authority.base58())
    assertEquals(m.u64("auto_return"), miner.autoReturn)
    assertEquals(m.u64("checkpoint_id"), miner.checkpointId)
    assertEquals(m.u64("checkpoint_fee"), miner.checkpointFee)
    assertEquals(m.squares("deployed"), miner.deployed)
    assertEquals(m.squares("mass"), miner.mass)
    assertEquals(m.squares("cumulative"), miner.cumulative)
    assertEquals(m.u64("round_id"), miner.roundId)
    assertEquals(m.u64("rewards_sol"), miner.rewardsSol)
    assertEquals(m.u64("refined_ore"), miner.refinedOre)
    assertEquals(m.u64("rewards_ore"), miner.rewardsOre)
    assertEquals(m.u64("last_claim_ore_at").toLong(), miner.lastClaimOreAt)
    assertEquals(m.u64("last_claim_sol_at").toLong(), miner.lastClaimSolAt)
    assertEquals(m.u64("lifetime_rewards_ore"), miner.lifetimeRewardsOre)
    assertEquals(m.u64("lifetime_deployed"), miner.lifetimeDeployed)
    assertEquals(m.u64("lifetime_rewards_sol"), miner.lifetimeRewardsSol)

    val round = OreRound.decode(data("round"))
    val r = fixtures.getValue("round").jsonObject
    assertEquals(r.u64("id"), round.id)
    assertEquals(r.squares("deployed"), round.deployed)
    assertEquals(r.squares("mass"), round.mass)
    assertEquals(r.squares("count"), round.count)
    assertEquals(
      r.getValue("entropy").jsonPrimitive.content,
      round.entropy.joinToString("") { it.toUByte().toString(16).padStart(2, '0') },
    )
    assertEquals(r.u64("expires_at"), round.expiresAt)
    assertEquals(r.u64("motherlode"), round.motherlode)
    assertEquals(r.getValue("rent_payer").jsonPrimitive.content, round.rentPayer.base58())
    assertEquals(r.squares("rewards"), round.rewards)
    assertEquals(r.u64("total_vaulted"), round.totalVaulted)
    assertEquals(r.u64("total_returned_sol"), round.totalReturnedSol)
    assertEquals(r.u64("total_miners"), round.totalMiners)
    assertEquals(r.getValue("top_miner").jsonPrimitive.content, round.topMiner.base58())

    val treasury = OreTreasury.decode(data("treasury"))
    val t = fixtures.getValue("treasury").jsonObject
    assertEquals(t.u64("motherlode"), treasury.motherlode)
    assertEquals(t.u64("total_refined"), treasury.totalRefined)
    assertEquals(t.u64("total_unclaimed"), treasury.totalUnclaimed)
  }

  @Test
  fun rejectsIncorrectAccountLayouts() {
    val decoders: List<Pair<String, (ByteArray) -> Any>> =
      listOf(
        "board" to OreBoard::decode,
        "config" to OreConfig::decode,
        "miner" to OreMiner::decode,
        "round" to OreRound::decode,
        "treasury" to OreTreasury::decode,
      )
    for ((name, decode) in decoders) {
      val valid = data(name)
      assertFailsWith<IllegalArgumentException> { decode(valid.copyOf(valid.size - 1)) }
      assertFailsWith<IllegalArgumentException> { decode(valid + byteArrayOf(0)) }
      assertFailsWith<IllegalArgumentException> { decode(valid.copyOf().also { it[0] = 0 }) }
    }
  }

  @Test
  fun preservesSignedTimestampsAndFixedPointBits() {
    val miner = data("miner").copyOf()
    ByteArray(16) { -1 }.copyInto(miner, 672)
    ByteArray(8) { -1 }.copyInto(miner, 712)
    val decoded = OreMiner.decode(miner)
    assertEquals(OreNumeric(ULong.MAX_VALUE, -1), decoded.rewardsFactor)
    assertEquals(-1, decoded.lastClaimOreAt)
    val treasury = data("treasury").copyOf()
    ByteArray(16) { -1 }.copyInto(treasury, 16)
    assertEquals(OreNumeric(ULong.MAX_VALUE, -1), OreTreasury.decode(treasury).minerRewardsFactor)
  }

  private fun data(name: String): ByteArray =
    Base64.decode(
      fixtures
        .getValue("accounts")
        .jsonObject
        .getValue(name)
        .jsonObject
        .getValue("data")
        .jsonArray[0]
        .jsonPrimitive
        .content
    )

  private fun JsonObject.u64(name: String): ULong = getValue(name).jsonPrimitive.content.toULong()

  private fun JsonObject.squares(name: String): List<ULong> =
    getValue(name).jsonArray.map { it.jsonPrimitive.content.toULong() }
}
