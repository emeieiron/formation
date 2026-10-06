package xyz.mcxross.formation.solana

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.TransactionInstruction
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class IdlContractTest {
  private val idl =
    Json.parseToJsonElement(File(System.getProperty("formation.idl")).readText()).jsonObject
  private val vault = FormationVault()
  private val a = SolanaPublicKey(ByteArray(32) { 1 })
  private val b = SolanaPublicKey(ByteArray(32) { 2 })
  private val c = SolanaPublicKey(ByteArray(32) { 3 })
  private val proof = listOf(ByteArray(32) { 7 }, ByteArray(32) { 8 })
  private val settings =
    mapOf(
      "paused" to false,
      "modes" to 3,
      "owner_weight" to 3,
      "min_guest_share" to 10_000_000uL,
      "min_budgets" to 10,
      "max_per_key" to 3,
      "max_wins_per_sgt" to 2,
      "guest_ceiling" to 64,
      "claim_window" to 2_592_000L,
      "min_play_window" to 3_600L,
      "max_play_window" to 7_776_000L,
      "min_enter_window" to 3_600L,
      "max_enter_window" to 2_592_000L,
      "draw_timeout" to 3_600L,
      "test_tokens" to true,
    )

  private fun named(section: String, name: String) =
    idl
      .getValue(section)
      .jsonArray
      .map { it.jsonObject }
      .single { it.getValue("name").jsonPrimitive.content == name }

  private fun JsonObject.discriminator() =
    getValue("discriminator").jsonArray.map { it.jsonPrimitive.int.toByte() }.toByteArray()

  @Test
  fun programIdMatches() {
    assertEquals(FormationVault.PROGRAM_ID.base58(), idl.getValue("address").jsonPrimitive.content)
  }

  @Test
  fun discriminatorsMatch() {
    assertContentEquals(named("instructions", "unlock").discriminator(), FormationVault.UNLOCK)
    assertContentEquals(named("instructions", "unlock_drawn").discriminator(), FormationVault.UNLOCK_DRAWN)
    assertContentEquals(named("instructions", "register").discriminator(), FormationVault.REGISTER)
    assertContentEquals(named("instructions", "claim").discriminator(), FormationVault.CLAIM)
    assertContentEquals(named("instructions", "mint_test_token").discriminator(), FormationVault.MINT_TEST_TOKEN)
    assertContentEquals(named("accounts", "Config").discriminator(), VaultConfig.DISCRIMINATOR)
    assertContentEquals(named("accounts", "Contest").discriminator(), VaultContest.DISCRIMINATOR)
    assertContentEquals(named("accounts", "Entry").discriminator(), VaultEntry.DISCRIMINATOR)
  }

  private suspend fun instructions(): List<Triple<String, TransactionInstruction, Map<String, Any>>> {
    val holder = FormationVault.Holder(a, b, c)
    val root = ByteArray(32) { 9 }
    val result = ByteArray(32) { 10 }
    return listOf(
      Triple(
        "unlock",
        vault.unlock(holder, c, b, 1, root, 4, result),
        mapOf("round" to 1, "roster_root" to root, "roster_size" to 4, "result" to result),
      ),
      Triple(
        "unlock_drawn",
        vault.unlockDrawn(holder, c, b, root, 4, result),
        mapOf("roster_root" to root, "roster_size" to 4, "result" to result),
      ),
      Triple("register", vault.register(holder, c), emptyMap()),
      Triple("mint_test_token", vault.mintTestToken(a, b, c), emptyMap()),
      Triple(
        "claim",
        vault.claim(a, b, c, a, b, c, 3, proof, claimerSigns = false),
        mapOf("index" to 3, "proof" to proof),
      ),
    )
  }

  @Test
  fun accountListsMatchTheIdl() = runTest {
    for ((name, ix, _) in instructions()) {
      val expected = named("instructions", name).getValue("accounts").jsonArray.map { it.jsonObject }
      assertEquals(expected.map { it.getValue("name").jsonPrimitive.content }.size, ix.accounts.size, name)
      expected.zip(ix.accounts).forEach { (spec, meta) ->
        val label = "$name.${spec.getValue("name").jsonPrimitive.content}"
        assertEquals(spec["signer"]?.jsonPrimitive?.content == "true", meta.isSigner, "$label signer")
        assertEquals(spec["writable"]?.jsonPrimitive?.content == "true", meta.isWritable, "$label writable")
        spec["address"]?.let { assertEquals(it.jsonPrimitive.content, meta.publicKey.base58(), "$label address") }
      }
    }
  }

  @Test
  fun argumentsEncodeAsTheIdlSays() = runTest {
    for ((name, ix, values) in instructions()) {
      val out = BorshWriter().bytes(named("instructions", name).discriminator())
      named("instructions", name).getValue("args").jsonArray.forEach { arg ->
        val argName = arg.jsonObject.getValue("name").jsonPrimitive.content
        encode(arg.jsonObject.getValue("type"), values.getValue(argName), out)
      }
      assertContentEquals(out.toByteArray(), ix.data, name)
    }
  }

  @Test
  fun contestLayoutMatchesTheIdl() {
    val sample =
      mapOf(
        "sponsor" to a,
        "nonce" to 7uL,
        "mint" to b,
        "vault" to c,
        "sgt_group" to a,
        "vrf_program" to b,
        "mode" to 2,
        "draw_bps" to 2_500,
        "only" to SolanaPublicKey(ByteArray(32)),
        "wins_per_sgt" to 1,
        "pool" to 3_000_000_000uL,
        "unallocated" to 2_000_000_000uL,
        "budget" to 300_000_000uL,
        "max_guests" to 27,
        "settings" to settings,
        "created_at" to 1_700_000_000L,
        "enter_until" to 1_700_086_400L,
        "play_until" to 1_700_172_800L,
        "entered" to 40L,
        "selected" to 10L,
        "unlocks" to 3L,
        "draw" to
          mapOf(
            "seed" to ByteArray(32) { 4 },
            "requested_at" to 1_700_090_000L,
            "attempts" to 1,
            "randomness" to ByteArray(32) { 5 },
            "done" to true,
          ),
        "title" to ByteArray(32).also { "Night Sky".encodeToByteArray().copyInto(it) },
        "branding" to ByteArray(32) { 6 },
        "bump" to 254,
      )
    val contest = VaultContest.decode(c, layout("Contest", sample))
    assertEquals(listOf(a, b, c, a, b), listOf(contest.sponsor, contest.mint, contest.vault, contest.sgtGroup, contest.vrfProgram))
    assertEquals(7uL, contest.nonce)
    assertEquals(ContestMode.DRAW, contest.mode)
    assertEquals(2_500, contest.drawBps)
    assertEquals(null, contest.only)
    assertEquals(listOf(3_000_000_000uL, 2_000_000_000uL, 300_000_000uL), listOf(contest.pool, contest.unallocated, contest.budget))
    assertEquals(listOf(1, 27), listOf(contest.winsPerSgt, contest.maxGuests))
    assertEquals(VaultSettings(false, 3, 3, 10_000_000uL, 10, 3, 2, 64, 2_592_000, 3_600, 7_776_000, 3_600, 2_592_000, 3_600, true), contest.settings)
    assertEquals(listOf(1_700_000_000L, 1_700_086_400L, 1_700_172_800L), listOf(contest.createdAtSeconds, contest.enterUntilSeconds, contest.playUntilSeconds))
    assertEquals(listOf(40L, 10L, 3L), listOf(contest.entered, contest.selected, contest.unlocks))
    assertContentEquals(ByteArray(32) { 4 }, contest.drawSeed)
    assertEquals(listOf(1_700_090_000L, 1L), listOf(contest.drawRequestedAtSeconds, contest.drawAttempts.toLong()))
    assertContentEquals(ByteArray(32) { 5 }, contest.randomness)
    assertEquals(true, contest.drawn)
    assertEquals("Night Sky", contest.title)
    assertEquals(254, contest.bump)
  }

  @Test
  fun entryLayoutMatchesTheIdl() {
    val sample =
      mapOf(
        "contest" to a,
        "sgt_mint" to b,
        "round" to 2,
        "index" to 41L,
        "payer" to c,
        "owner" to a,
        "state" to 1,
        "budget" to 300_000_000uL,
        "roster_root" to ByteArray(32) { 11 },
        "roster_size" to 4,
        "guest_share" to 42_857_142uL,
        "owner_paid" to 128_571_432uL,
        "claimed" to 0b101uL,
        "result" to ByteArray(32) { 12 },
        "unlocked_at" to 1_750_000_000L,
        "closes_at" to 1_760_000_000L,
        "bump" to 253,
      )
    val data = layout("Entry", sample)
    assertContentEquals(b.bytes, data.copyOfRange(VaultEntry.SGT_OFFSET, VaultEntry.SGT_OFFSET + 32))
    val entry = VaultEntry.decode(c, data)
    assertEquals(listOf(a, b, c, a), listOf(entry.contest, entry.sgt, entry.payer, entry.owner))
    assertEquals(listOf(2L, 41L, 4L), listOf(entry.round.toLong(), entry.index, entry.rosterSize.toLong()))
    assertEquals(EntryState.UNLOCKED, entry.state)
    assertEquals(listOf(300_000_000uL, 42_857_142uL, 128_571_432uL), listOf(entry.budget, entry.guestShare, entry.ownerPaid))
    assertEquals(listOf(true, false, true), (0..2).map(entry::hasClaimed))
    assertContentEquals(ByteArray(32) { 11 }, entry.rosterRoot)
    assertContentEquals(ByteArray(32) { 12 }, entry.result)
    assertEquals(listOf(1_750_000_000L, 1_760_000_000L), listOf(entry.unlockedAtSeconds, entry.closesAtSeconds))
    assertEquals(253, entry.bump)
  }

  @Test
  fun configLayoutMatchesTheIdl() {
    val sample = mapOf("admin" to a, "mint" to b, "sgt_group" to c, "vrf_program" to a, "settings" to settings, "bump" to 7)
    val config = VaultConfig.decode(layout("Config", sample))
    assertEquals(listOf(a, b, c, a), listOf(config.admin, config.mint, config.sgtGroup, config.vrfProgram))
    assertEquals(3, config.settings.ownerWeight)
    assertEquals(7, config.bump)
  }

  @Test
  fun errorsMatchTheIdl() {
    val expected =
      idl.getValue("errors").jsonArray.map {
        it.jsonObject.getValue("code").jsonPrimitive.int to it.jsonObject.getValue("name").jsonPrimitive.content
      }
    val actual =
      VaultError.entries.map {
        it.code to it.name.split('_').joinToString("") { part -> part.lowercase().replaceFirstChar(Char::uppercase) }
      }
    assertEquals(expected, actual)
  }

  private fun layout(account: String, sample: Map<String, Any>): ByteArray {
    val out = BorshWriter().bytes(named("accounts", account).discriminator())
    encodeStruct(account, sample, out)
    return out.toByteArray()
  }

  private fun encodeStruct(type: String, value: Map<*, *>, out: BorshWriter) {
    val fields = named("types", type).getValue("type").jsonObject.getValue("fields").jsonArray.map {
      it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("type")
    }
    assertEquals(fields.map { it.first }.toSet(), value.keys, type)
    fields.forEach { (field, fieldType) -> encode(fieldType, value[field]!!, out) }
  }

  private fun encode(type: JsonElement, value: Any, out: BorshWriter) {
    if (type is JsonPrimitive) {
      when (val t = type.content) {
        "u8" -> out.u8(value as Int)
        "u16" -> out.u16(value as Int)
        "u32" -> out.u32((value as Long).toInt())
        "u64" -> out.u64(value as ULong)
        "i64" -> out.i64(value as Long)
        "bool" -> out.u8(if (value as Boolean) 1 else 0)
        "pubkey" -> out.key(value as SolanaPublicKey)
        else -> error("Unhandled IDL type $t")
      }
      return
    }
    val o = type.jsonObject
    when {
      "array" in o -> {
        val (inner, size) = o.getValue("array").jsonArray
        check(inner.jsonPrimitive.content == "u8")
        out.fixed(value as ByteArray, size.jsonPrimitive.int)
      }
      "vec" in o -> {
        val items = value as List<*>
        out.u32(items.size)
        items.forEach { encode(o.getValue("vec"), it!!, out) }
      }
      "defined" in o -> {
        val name = o.getValue("defined").jsonObject.getValue("name").jsonPrimitive.content
        when (named("types", name).getValue("type").jsonObject.getValue("kind").jsonPrimitive.content) {
          "struct" -> encodeStruct(name, value as Map<*, *>, out)
          else -> out.u8(value as Int)
        }
      }
      else -> error("Unhandled IDL type $o")
    }
  }
}
