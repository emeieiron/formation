package xyz.mcxross.formation.solana

import com.solana.programs.AssociatedTokenProgram
import com.solana.programs.SystemProgram
import com.solana.programs.TokenProgram
import com.solana.publickey.ProgramDerivedAddress
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.TransactionInstruction

class FormationVault(
  val programId: SolanaPublicKey = PROGRAM_ID,
  val tokenProgram: SolanaPublicKey = TokenProgram.PROGRAM_ID,
) {
  suspend fun config(): ProgramDerivedAddress = pda(CONFIG_SEED)

  suspend fun opportunity(id: ByteArray): ProgramDerivedAddress = pda(OPPORTUNITY_SEED, id16(id))

  suspend fun tokenAccount(owner: SolanaPublicKey, mint: SolanaPublicKey): SolanaPublicKey =
    associatedTokenAccount(owner, mint, tokenProgram)

  suspend fun vault(id: ByteArray, mint: SolanaPublicKey): SolanaPublicKey =
    tokenAccount(opportunity(id), mint)

  suspend fun create(
    sponsor: SolanaPublicKey,
    seeker: SolanaPublicKey,
    sgtMint: SolanaPublicKey,
    sgtAccount: SolanaPublicKey,
    mint: SolanaPublicKey,
    lock: Lock,
  ): TransactionInstruction {
    val opportunity = opportunity(lock.id)
    val data =
      BorshWriter()
        .bytes(CREATE)
        .fixed(lock.id, 16)
        .u64(lock.amount)
        .u8(lock.players)
        .u16(lock.ownerBps)
        .u16(lock.challenge)
        .u8(lock.difficulty)
        .fixed(titleBytes(lock.title), TITLE_BYTES)
        .i64(lock.expiresAtSeconds)
        .toByteArray()
    return instruction(
      data,
      signer(sponsor, writable = true),
      readOnly(config()),
      readOnly(seeker),
      readOnly(sgtMint),
      readOnly(sgtAccount),
      writable(opportunity),
      writable(tokenAccount(opportunity, mint)),
      writable(tokenAccount(sponsor, mint)),
      readOnly(mint),
      *programs(),
    )
  }

  suspend fun unlock(
    seeker: SolanaPublicKey,
    mint: SolanaPublicKey,
    id: ByteArray,
    rosterRoot: ByteArray,
    rosterSize: Int,
    result: ByteArray,
  ): TransactionInstruction {
    val opportunity = opportunity(id)
    val data =
      BorshWriter()
        .bytes(UNLOCK)
        .fixed(rosterRoot, 32)
        .u8(rosterSize)
        .fixed(result, 32)
        .toByteArray()
    return instruction(
      data,
      signer(seeker, writable = true),
      writable(opportunity),
      writable(tokenAccount(opportunity, mint)),
      writable(tokenAccount(seeker, mint)),
      readOnly(mint),
      *programs(),
    )
  }

  suspend fun claim(
    payer: SolanaPublicKey,
    claimer: SolanaPublicKey,
    recipient: SolanaPublicKey,
    mint: SolanaPublicKey,
    id: ByteArray,
    index: Int,
    proof: List<ByteArray>,
    claimerSigns: Boolean = true,
  ): TransactionInstruction {
    require(proof.size <= MAX_PROOF) { "A roster proof has at most $MAX_PROOF steps" }
    val opportunity = opportunity(id)
    val data =
      BorshWriter()
        .bytes(CLAIM)
        .u8(index)
        .u32(proof.size)
        .apply { proof.forEach { fixed(it, 32) } }
        .toByteArray()
    return instruction(
      data,
      signer(payer, writable = true),
      if (claimerSigns) signer(claimer, writable = false) else readOnly(claimer),
      readOnly(recipient),
      writable(opportunity),
      writable(tokenAccount(opportunity, mint)),
      writable(tokenAccount(recipient, mint)),
      readOnly(mint),
      *programs(),
    )
  }

  suspend fun close(
    payer: SolanaPublicKey,
    sponsor: SolanaPublicKey,
    mint: SolanaPublicKey,
    id: ByteArray,
  ): TransactionInstruction {
    val opportunity = opportunity(id)
    return instruction(
      CLOSE,
      signer(payer, writable = true),
      writable(sponsor),
      writable(opportunity),
      writable(tokenAccount(opportunity, mint)),
      writable(tokenAccount(sponsor, mint)),
      readOnly(mint),
      *programs(),
    )
  }

  private fun programs() =
    arrayOf(
      readOnly(tokenProgram),
      readOnly(AssociatedTokenProgram.PROGRAM_ID),
      readOnly(SystemProgram.PROGRAM_ID),
    )

  private fun instruction(data: ByteArray, vararg accounts: AccountMeta) =
    TransactionInstruction(programId, accounts.toList(), data)

  private suspend fun pda(vararg seeds: ByteArray) =
    ProgramDerivedAddress.find(seeds.toList(), programId).getOrThrow()

  class Lock(
    val id: ByteArray,
    val amount: ULong,
    val players: Int,
    val ownerBps: Int,
    val challenge: Int,
    val difficulty: Int,
    val title: String?,
    val expiresAtSeconds: Long,
  )

  companion object {
    val PROGRAM_ID = SolanaPublicKey.from("3AzZbKhGFcnaBRRenDDdNSdVumjKoXSkNHsPVeo5q6GW")

    val CONFIG_SEED = "config".encodeToByteArray()
    val OPPORTUNITY_SEED = "opportunity".encodeToByteArray()

    const val MAX_PROOF = 7
    const val TITLE_BYTES = 32
    const val CLAIM_WINDOW_SECONDS = 30L * 24 * 60 * 60

    internal val CREATE = discriminator(24, 30, 200, 40, 5, 28, 7, 119)
    internal val UNLOCK = discriminator(101, 155, 40, 21, 158, 189, 56, 203)
    internal val CLAIM = discriminator(62, 198, 214, 193, 213, 159, 108, 210)
    internal val CLOSE = discriminator(98, 165, 201, 177, 108, 65, 206, 96)

    // UTF-8, cut on a character boundary (never between surrogates), zero-padded to 32 bytes.
    fun titleBytes(title: String?): ByteArray {
      val out = ByteArray(TITLE_BYTES)
      val text = (title ?: "").trim()
      var used = 0
      var at = 0
      while (at < text.length) {
        val end = if (text[at].isHighSurrogate() && at + 1 < text.length) at + 2 else at + 1
        val encoded = text.substring(at, end).encodeToByteArray()
        if (used + encoded.size > TITLE_BYTES) break
        encoded.copyInto(out, used)
        used += encoded.size
        at = end
      }
      return out
    }

    fun titleOf(bytes: ByteArray): String? =
      bytes.takeWhile { it != 0.toByte() }.toByteArray().decodeToString().trim().ifEmpty { null }

    private fun id16(id: ByteArray): ByteArray {
      require(id.size == 16) { "An opportunity id is 16 bytes" }
      return id
    }
  }
}

suspend fun associatedTokenAccount(
  owner: SolanaPublicKey,
  mint: SolanaPublicKey,
  tokenProgram: SolanaPublicKey = TokenProgram.PROGRAM_ID,
): SolanaPublicKey =
  ProgramDerivedAddress.find(
      listOf(owner.bytes, tokenProgram.bytes, mint.bytes),
      AssociatedTokenProgram.PROGRAM_ID,
    )
    .getOrThrow()

private fun signer(key: SolanaPublicKey, writable: Boolean) =
  AccountMeta(key, isSigner = true, isWritable = writable)

private fun writable(key: SolanaPublicKey) = AccountMeta(key, isSigner = false, isWritable = true)

private fun readOnly(key: SolanaPublicKey) = AccountMeta(key, isSigner = false, isWritable = false)
