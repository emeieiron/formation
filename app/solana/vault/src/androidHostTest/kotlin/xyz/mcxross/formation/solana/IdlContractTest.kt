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
  private val id = ByteArray(16) { it.toByte() }
  private val proof = listOf(ByteArray(32) { 7 }, ByteArray(32) { 8 })

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
    assertContentEquals(named("instructions", "create").discriminator(), FormationVault.CREATE)
    assertContentEquals(named("instructions", "unlock").discriminator(), FormationVault.UNLOCK)
    assertContentEquals(named("instructions", "claim").discriminator(), FormationVault.CLAIM)
    assertContentEquals(named("instructions", "close").discriminator(), FormationVault.CLOSE)
    assertContentEquals(named("accounts", "Config").discriminator(), VaultConfig.DISCRIMINATOR)
    assertContentEquals(
      named("accounts", "Opportunity").discriminator(),
      VaultOpportunity.DISCRIMINATOR,
    )
  }

  private suspend fun instructions():
    List<Triple<String, TransactionInstruction, Map<String, Any>>> {
    val lock = FormationVault.Lock(id, 600_000_000uL, 5, 5_000, 3, 2, "Night Sky", 1_800_000_000L)
    val root = ByteArray(32) { 9 }
    val result = ByteArray(32) { 10 }
    return listOf(
      Triple(
        "create",
        vault.create(a, b, c, a, b, lock),
        mapOf(
          "id" to id,
          "amount" to 600_000_000uL,
          "players" to 5,
          "owner_bps" to 5_000,
          "challenge" to 3,
          "difficulty" to 2,
          "title" to FormationVault.titleBytes("Night Sky"),
          "expires_at" to 1_800_000_000L,
        ),
      ),
      Triple(
        "unlock",
        vault.unlock(a, b, id, root, 4, result),
        mapOf("roster_root" to root, "roster_size" to 4, "result" to result),
      ),
      Triple(
        "claim",
        vault.claim(a, b, c, a, id, 3, proof, claimerSigns = false),
        mapOf("index" to 3, "proof" to proof),
      ),
      Triple("close", vault.close(a, b, c, id), emptyMap()),
    )
  }

  @Test
  fun accountListsMatchTheIdl() = runTest {
    for ((name, ix, _) in instructions()) {
      val expected =
        named("instructions", name).getValue("accounts").jsonArray.map { it.jsonObject }
      assertEquals(expected.size, ix.accounts.size, name)
      expected.zip(ix.accounts).forEach { (spec, meta) ->
        val label = "$name.${spec.getValue("name").jsonPrimitive.content}"
        assertEquals(
          spec["signer"]?.jsonPrimitive?.content == "true",
          meta.isSigner,
          "$label signer",
        )
        assertEquals(
          spec["writable"]?.jsonPrimitive?.content == "true",
          meta.isWritable,
          "$label writable",
        )
        spec["address"]?.let {
          assertEquals(it.jsonPrimitive.content, meta.publicKey.base58(), "$label address")
        }
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
  fun opportunityLayoutMatchesTheIdl() {
    val fields = fields("Opportunity")
    val sample =
      mapOf(
        "id" to id,
        "sponsor" to a,
        "seeker" to b,
        "sgt" to c,
        "mint" to a,
        "vault" to b,
        "amount" to 600_000_000uL,
        "players" to 5,
        "owner_bps" to 4_000,
        "challenge" to 4,
        "difficulty" to 3,
        "title" to FormationVault.titleBytes("Night Sky"),
        "created_at" to 1_700_000_000L,
        "expires_at" to 1_800_000_000L,
        "state" to 1,
        "roster_root" to ByteArray(32) { 11 },
        "roster_size" to 4,
        "helper_share" to 75_000_000uL,
        "claimed" to 0b101uL,
        "result" to ByteArray(32) { 12 },
        "unlocked_at" to 1_750_000_000L,
        "bump" to 253,
      )
    assertEquals(fields.map { it.first }.toSet(), sample.keys)

    val out = BorshWriter().bytes(named("accounts", "Opportunity").discriminator())
    var offset = 8
    val offsets = mutableMapOf<String, Int>()
    for ((field, type) in fields) {
      offsets[field] = offset
      val before = out.toByteArray().size
      encode(type, sample.getValue(field), out)
      offset += out.toByteArray().size - before
    }
    assertEquals(VaultOpportunity.SEEKER_OFFSET, offsets["seeker"])
    assertEquals(VaultOpportunity.STATE_OFFSET, offsets["state"])
    assertEquals(VaultOpportunity.SIZE, offset)

    val o = VaultOpportunity.decode(c, out.toByteArray())
    assertContentEquals(id, o.id)
    assertEquals(listOf(a, b, c, a, b), listOf(o.sponsor, o.seeker, o.sgt, o.mint, o.vault))
    assertEquals(600_000_000uL, o.amount)
    assertEquals(listOf(5, 4_000, 4, 3), listOf(o.players, o.ownerBps, o.challenge, o.difficulty))
    assertEquals("Night Sky", o.title)
    assertEquals(
      listOf(1_700_000_000L, 1_800_000_000L, 1_750_000_000L),
      listOf(o.createdAtSeconds, o.expiresAtSeconds, o.unlockedAtSeconds),
    )
    assertEquals(VaultState.UNLOCKED, o.state)
    assertContentEquals(ByteArray(32) { 11 }, o.rosterRoot)
    assertEquals(4, o.rosterSize)
    assertEquals(75_000_000uL, o.helperShare)
    assertEquals(listOf(true, false, true), (0..2).map(o::hasClaimed))
    assertContentEquals(ByteArray(32) { 12 }, o.result)
    assertEquals(253, o.bump)
  }

  @Test
  fun configLayoutMatchesTheIdl() {
    val out = BorshWriter().bytes(named("accounts", "Config").discriminator())
    val sample = mapOf("admin" to a, "mint" to b, "sgt_group" to c, "bump" to 7)
    fields("Config").forEach { (field, type) -> encode(type, sample.getValue(field), out) }
    assertEquals(VaultConfig(a, b, c, 7), VaultConfig.decode(out.toByteArray()))
  }

  @Test
  fun errorsMatchTheIdl() {
    val expected =
      idl.getValue("errors").jsonArray.map {
        it.jsonObject.getValue("code").jsonPrimitive.int to
          it.jsonObject.getValue("name").jsonPrimitive.content
      }
    val actual =
      VaultError.entries.map {
        it.code to
          it.name.split('_').joinToString("") { part ->
            part.lowercase().replaceFirstChar(Char::uppercase)
          }
      }
    assertEquals(expected, actual)
  }

  private fun fields(type: String): List<Pair<String, JsonElement>> =
    named("types", type).getValue("type").jsonObject.getValue("fields").jsonArray.map {
      it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("type")
    }

  private fun encode(type: JsonElement, value: Any, out: BorshWriter) {
    if (type is JsonPrimitive) {
      when (val t = type.content) {
        "u8" -> out.u8(value as Int)
        "u16" -> out.u16(value as Int)
        "u64" -> out.u64(value as ULong)
        "i64" -> out.i64(value as Long)
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
        check(
          named("types", name).getValue("type").jsonObject.getValue("kind").jsonPrimitive.content ==
            "enum"
        )
        out.u8(value as Int)
      }
      else -> error("Unhandled IDL type $o")
    }
  }
}
