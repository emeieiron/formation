package xyz.mcxross.formation.solana.ore

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.TransactionInstruction
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OreProgramTest {
  private val program = OreProgram()
  private val authority = SolanaPublicKey.from("9U7jMaMyBBdxxEp4zA5L8DgovQBPsdx6njY5EMXaxEMy")

  @Test
  fun addressesMatchTheDeployment() = runTest {
    assertEquals("AHvr5zQU7UgbZ6Lf1ZzyVZNjb8DYLpBVnC9eApd2vgCH", program.board().base58())
    assertEquals("7rc8rw4uHhUZsM2xgkBw3uW9KMKSgEhd9zWaxKnyPmZa", program.config().base58())
    assertEquals("BqTBRbk6DvwUH1ydBAD8DCYCv2hoygBPJ2MFH8S8vHGr", program.treasury().base58())
    assertEquals("6CVbEiJUsrgZ6jK8MHHS6bTSaDx39HN1RaJqVX6Qv35V", program.entropyVar().base58())
  }

  @Test
  fun instructionsMatchTheDeploymentHarness() = runTest {
    assertInstruction(
      "deploy",
      program.deploy(authority, authority, 4uL, 1_000_000uL, setOf(0, 16, 24)),
    )
    assertInstruction("checkpoint", program.checkpoint(authority, authority, 3uL))
    assertInstruction("claimOre", program.claimOre(authority))
    val claim = program.claimSol(authority)
    assertContentEquals(byteArrayOf(3), claim.data)
    assertEquals(
      listOf(
        authority,
        program.board(),
        program.miner(authority),
        SolanaPublicKey.from("11111111111111111111111111111111"),
        program.programId,
      ),
      claim.accounts.map { it.publicKey },
    )
    assertEquals(listOf(true, false, false, false, false), claim.accounts.map { it.isSigner })
    assertEquals(listOf(true, true, true, false, false), claim.accounts.map { it.isWritable })
    assertContentEquals(
      byteArrayOf(4, -60, 9, 0, 0, 0, 0, 0, 0),
      program.claimOre(authority, 2500).data,
    )
  }

  @Test
  fun payerAndMinerAuthorityCanDiffer() = runTest {
    val payer = SolanaPublicKey(ByteArray(32) { 1 })
    val deploy = program.deploy(payer, authority, 4uL, 1uL, setOf(24))
    assertEquals(payer, deploy.accounts[0].publicKey)
    assertEquals(authority, deploy.accounts[1].publicKey)
    assertEquals(program.miner(authority), deploy.accounts[5].publicKey)
    assertEquals(listOf(true) + List(11) { false }, deploy.accounts.map { it.isSigner })
    val checkpoint = program.checkpoint(payer, authority, 3uL)
    assertEquals(payer, checkpoint.accounts[0].publicKey)
    assertEquals(authority, checkpoint.accounts[1].publicKey)
    assertEquals(program.miner(authority), checkpoint.accounts[4].publicKey)
  }

  @Test
  fun invalidDeploymentsAndClaimsFailBeforeSigning() = runTest {
    for (squares in listOf(emptySet(), setOf(-1), setOf(25))) {
      assertFailsWith<IllegalArgumentException> {
        program.deploy(authority, authority, 4uL, 1uL, squares)
      }
    }
    assertFailsWith<IllegalArgumentException> {
      program.deploy(authority, authority, 4uL, 0uL, setOf(0))
    }
    assertFailsWith<IllegalArgumentException> {
      program.deploy(authority, authority, 4uL, ULong.MAX_VALUE, setOf(0, 1))
    }
    for (bps in listOf(-1, 0, 10001)) {
      assertFailsWith<IllegalArgumentException> { program.claimOre(authority, bps) }
    }
  }

  private fun assertInstruction(name: String, instruction: TransactionInstruction) {
    val expected =
      Json.parseToJsonElement(oreFixtures)
        .jsonObject
        .getValue("instructions")
        .jsonObject
        .getValue(name)
        .jsonObject
    assertEquals(program.programId, instruction.programId)
    assertContentEquals(
      expected.getValue("data").jsonArray.map { it.jsonPrimitive.int.toByte() }.toByteArray(),
      instruction.data,
    )
    val accounts = expected.getValue("accounts").jsonArray.map { it.jsonArray }
    assertEquals(
      accounts.map { it[0].jsonPrimitive.content },
      instruction.accounts.map { it.publicKey.base58() },
    )
    assertEquals(
      accounts.map { it[1].jsonPrimitive.boolean },
      instruction.accounts.map { it.isSigner },
    )
    assertEquals(
      accounts.map { it[2].jsonPrimitive.boolean },
      instruction.accounts.map { it.isWritable },
    )
  }
}
