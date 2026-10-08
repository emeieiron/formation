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

  suspend fun contest(sponsor: SolanaPublicKey, nonce: ULong): ProgramDerivedAddress =
    pda(CONTEST_SEED, sponsor.bytes, BorshWriter().u64(nonce).toByteArray())

  suspend fun entry(
    contest: SolanaPublicKey,
    sgt: SolanaPublicKey,
    round: Int,
  ): ProgramDerivedAddress = pda(ENTRY_SEED, contest.bytes, sgt.bytes, byteArrayOf(round.toByte()))

  suspend fun receipt(contest: SolanaPublicKey, claimKey: SolanaPublicKey): ProgramDerivedAddress =
    pda(RECEIPT_SEED, contest.bytes, claimKey.bytes)

  suspend fun testAuthority(): ProgramDerivedAddress = pda(TEST_AUTHORITY_SEED)

  suspend fun testToken(wallet: SolanaPublicKey): ProgramDerivedAddress =
    pda(TEST_TOKEN_SEED, wallet.bytes)

  // Mints [wallet] its test Genesis Token in [group]; the faucet pays as [payer].
  suspend fun mintTestToken(
    payer: SolanaPublicKey,
    wallet: SolanaPublicKey,
    group: SolanaPublicKey,
  ): TransactionInstruction {
    val mint = testToken(wallet)
    return instruction(
      MINT_TEST_TOKEN,
      signer(payer, writable = true),
      readOnly(wallet),
      readOnly(config()),
      readOnly(testAuthority()),
      writable(group),
      writable(mint),
      writable(associatedTokenAccount(wallet, mint, SeekerGenesis.TOKEN_2022)),
      readOnly(SeekerGenesis.TOKEN_2022),
      readOnly(AssociatedTokenProgram.PROGRAM_ID),
      readOnly(SystemProgram.PROGRAM_ID),
    )
  }

  suspend fun tokenAccount(owner: SolanaPublicKey, mint: SolanaPublicKey): SolanaPublicKey =
    associatedTokenAccount(owner, mint, tokenProgram)

  // The SGT's current holder signs; the entry for [round] must not exist yet.
  suspend fun unlock(
    holder: Holder,
    contest: SolanaPublicKey,
    mint: SolanaPublicKey,
    round: Int,
    rosterRoot: ByteArray,
    rosterSize: Int,
    result: ByteArray,
  ): TransactionInstruction {
    val data =
      BorshWriter()
        .bytes(UNLOCK)
        .u8(round)
        .fixed(rosterRoot, 32)
        .u8(rosterSize)
        .fixed(result, 32)
        .toByteArray()
    return instruction(data, *settleAccounts(holder, contest, mint, round))
  }

  suspend fun unlockDrawn(
    holder: Holder,
    contest: SolanaPublicKey,
    mint: SolanaPublicKey,
    rosterRoot: ByteArray,
    rosterSize: Int,
    result: ByteArray,
  ): TransactionInstruction {
    val data =
      BorshWriter()
        .bytes(UNLOCK_DRAWN)
        .fixed(rosterRoot, 32)
        .u8(rosterSize)
        .fixed(result, 32)
        .toByteArray()
    return instruction(data, *settleAccounts(holder, contest, mint, 0))
  }

  suspend fun register(holder: Holder, contest: SolanaPublicKey): TransactionInstruction =
    instruction(
      REGISTER,
      signer(holder.wallet, writable = true),
      writable(contest),
      readOnly(holder.sgt),
      readOnly(holder.sgtAccount),
      writable(entry(contest, holder.sgt, 0)),
      readOnly(SystemProgram.PROGRAM_ID),
    )

  suspend fun claim(
    payer: SolanaPublicKey,
    claimer: SolanaPublicKey,
    recipient: SolanaPublicKey,
    contest: SolanaPublicKey,
    entry: SolanaPublicKey,
    mint: SolanaPublicKey,
    index: Int,
    proof: List<ByteArray>,
    claimerSigns: Boolean = true,
  ): TransactionInstruction {
    require(proof.size <= MAX_PROOF) { "A roster proof has at most $MAX_PROOF steps" }
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
      readOnly(contest),
      writable(entry),
      writable(receipt(contest, claimer)),
      writable(tokenAccount(contest, mint)),
      writable(tokenAccount(recipient, mint)),
      readOnly(mint),
      *programs(),
    )
  }

  private suspend fun settleAccounts(
    holder: Holder,
    contest: SolanaPublicKey,
    mint: SolanaPublicKey,
    round: Int,
  ) =
    arrayOf(
      signer(holder.wallet, writable = true),
      writable(contest),
      readOnly(holder.sgt),
      readOnly(holder.sgtAccount),
      writable(entry(contest, holder.sgt, round)),
      writable(tokenAccount(contest, mint)),
      writable(tokenAccount(holder.wallet, mint)),
      readOnly(mint),
      *programs(),
    )

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

  // A wallet, the Genesis Token it holds, and the token account holding it.
  class Holder(
    val wallet: SolanaPublicKey,
    val sgt: SolanaPublicKey,
    val sgtAccount: SolanaPublicKey,
  )

  companion object {
    val PROGRAM_ID = SolanaPublicKey.from("8haw7C2rGLgF4dmn3kciRERtrLmRFQLX6Hg14Hg4Jvg5")

    val CONFIG_SEED = "config".encodeToByteArray()
    val CONTEST_SEED = "contest".encodeToByteArray()
    val ENTRY_SEED = "entry".encodeToByteArray()
    val RECEIPT_SEED = "receipt".encodeToByteArray()
    val TEST_AUTHORITY_SEED = "test-authority".encodeToByteArray()
    val TEST_TOKEN_SEED = "test-token".encodeToByteArray()

    const val MAX_PROOF = 7
    const val TITLE_BYTES = 32

    internal val UNLOCK = discriminator(101, 155, 40, 21, 158, 189, 56, 203)
    internal val UNLOCK_DRAWN = discriminator(123, 145, 129, 39, 82, 70, 141, 210)
    internal val REGISTER = discriminator(211, 124, 67, 15, 211, 194, 178, 240)
    internal val CLAIM = discriminator(62, 198, 214, 193, 213, 159, 108, 210)
    internal val MINT_TEST_TOKEN = discriminator(173, 1, 219, 194, 158, 97, 116, 40)

    fun titleOf(bytes: ByteArray): String? =
      bytes.takeWhile { it != 0.toByte() }.toByteArray().decodeToString().trim().ifEmpty { null }
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
