package xyz.mcxross.formation.solana.ore

import com.solana.programs.AssociatedTokenProgram
import com.solana.programs.SystemProgram
import com.solana.programs.TokenProgram
import com.solana.publickey.ProgramDerivedAddress
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.TransactionInstruction
import xyz.mcxross.formation.solana.associatedTokenAccount

class OreProgram(val deployment: OreDeployment = OreDeployment.Devnet) {
  val programId: SolanaPublicKey
    get() = deployment.programId

  suspend fun board(): ProgramDerivedAddress = pda("board")

  suspend fun config(): ProgramDerivedAddress = pda("config")

  suspend fun treasury(): ProgramDerivedAddress = pda("treasury")

  suspend fun miner(authority: SolanaPublicKey): ProgramDerivedAddress =
    pda("miner", authority.bytes)

  suspend fun automation(authority: SolanaPublicKey): ProgramDerivedAddress =
    pda("automation", authority.bytes)

  suspend fun round(id: ULong): ProgramDerivedAddress = pda("round", id.littleEndian())

  suspend fun entropyVar(): ProgramDerivedAddress =
    ProgramDerivedAddress.find(
        listOf("var".encodeToByteArray(), board().bytes, 0uL.littleEndian()),
        deployment.entropyProgramId,
      )
      .getOrThrow()

  suspend fun tokenAccount(owner: SolanaPublicKey): SolanaPublicKey =
    associatedTokenAccount(owner, deployment.mint)

  suspend fun deploy(
    signer: SolanaPublicKey,
    authority: SolanaPublicKey,
    roundId: ULong,
    amountLamports: ULong,
    squares: Set<Int>,
  ): TransactionInstruction {
    require(squares.isNotEmpty()) { "Select at least one square" }
    require(squares.all { it in 0 until SQUARE_COUNT }) { "Square indices must be in 0..24" }
    require(amountLamports > 0uL && amountLamports <= ULong.MAX_VALUE / squares.size.toULong()) {
      "Deployment amount must be positive and its total must fit a u64"
    }
    val mask = squares.fold(0) { value, index -> value or (1 shl index) }
    val data =
      byteArrayOf(6) + amountLamports.littleEndian() + ByteArray(4) { (mask shr (it * 8)).toByte() }
    return instruction(
      data,
      signer(signer),
      writable(authority),
      writable(automation(authority)),
      writable(board()),
      writable(config()),
      writable(miner(authority)),
      writable(round(roundId)),
      writable(treasury()),
      readOnly(SystemProgram.PROGRAM_ID),
      readOnly(programId),
      writable(entropyVar()),
      readOnly(deployment.entropyProgramId),
    )
  }

  suspend fun checkpoint(
    signer: SolanaPublicKey,
    authority: SolanaPublicKey,
    roundId: ULong,
  ): TransactionInstruction =
    instruction(
      byteArrayOf(2),
      signer(signer),
      writable(authority),
      writable(automation(authority)),
      writable(board()),
      writable(miner(authority)),
      writable(round(roundId)),
      writable(treasury()),
      readOnly(SystemProgram.PROGRAM_ID),
    )

  suspend fun claimSol(signer: SolanaPublicKey): TransactionInstruction =
    instruction(
      byteArrayOf(3),
      signer(signer),
      writable(board()),
      writable(miner(signer)),
      readOnly(SystemProgram.PROGRAM_ID),
      readOnly(programId),
    )

  suspend fun claimOre(signer: SolanaPublicKey, basisPoints: Int = 10_000): TransactionInstruction {
    require(basisPoints in 1..10_000) { "Claim basis points must be in 1..10000" }
    val treasury = treasury()
    return instruction(
      byteArrayOf(4) + basisPoints.toULong().littleEndian(),
      signer(signer),
      writable(board()),
      writable(miner(signer)),
      writable(deployment.mint),
      writable(tokenAccount(signer)),
      writable(treasury),
      writable(tokenAccount(treasury)),
      readOnly(SystemProgram.PROGRAM_ID),
      readOnly(TokenProgram.PROGRAM_ID),
      readOnly(AssociatedTokenProgram.PROGRAM_ID),
      readOnly(programId),
    )
  }

  private suspend fun pda(seed: String, vararg seeds: ByteArray): ProgramDerivedAddress =
    ProgramDerivedAddress.find(listOf(seed.encodeToByteArray()) + seeds, programId).getOrThrow()

  private fun instruction(data: ByteArray, vararg accounts: AccountMeta) =
    TransactionInstruction(programId, accounts.toList(), data)

  companion object {
    const val SQUARE_COUNT = 25
  }
}

private fun signer(key: SolanaPublicKey) = AccountMeta(key, isSigner = true, isWritable = true)

private fun writable(key: SolanaPublicKey) = AccountMeta(key, isSigner = false, isWritable = true)

private fun readOnly(key: SolanaPublicKey) = AccountMeta(key, isSigner = false, isWritable = false)
