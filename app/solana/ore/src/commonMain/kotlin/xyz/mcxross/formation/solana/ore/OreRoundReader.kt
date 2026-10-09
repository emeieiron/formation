package xyz.mcxross.formation.solana.ore

import com.solana.publickey.ProgramDerivedAddress
import com.solana.publickey.SolanaPublicKey
import xyz.mcxross.formation.solana.SolanaRpc

interface OreRounds {
  suspend fun nextRound(): Long

  suspend fun result(id: Long): OreRoundResult
}

/** Read-only round results from the public ORE program. Use a finalized RPC connection. */
class OreRoundReader(private val rpc: SolanaRpc) : OreRounds {
  private var verified = false

  override suspend fun nextRound(): Long {
    verifyCluster()
    val board = board()
    check(board.roundId < Long.MAX_VALUE.toULong()) { "ORE round ID is out of range" }
    return board.roundId.toLong() + 1
  }

  override suspend fun result(id: Long): OreRoundResult {
    require(id > 0)
    verifyCluster()
    val key =
      ProgramDerivedAddress.find(
          listOf("round".encodeToByteArray(), id.toULong().littleEndian()),
          PROGRAM_ID,
        )
        .getOrThrow()
    // One bank snapshot prevents mixing a new board with a stale round account.
    val accounts = rpc.multipleAccounts(listOf(BOARD, key))
    val board = OreBoard.decode(owned(checkNotNull(accounts[0]) { "ORE board is unavailable" }))
    if (board.roundId <= id.toULong()) return OreRoundResult.Pending
    val account = accounts[1] ?: return OreRoundResult.Unavailable
    val round = OreRound.decode(owned(account))
    check(round.id == id.toULong()) { "ORE round ID does not match its address" }
    return winningNumber(round.entropy)?.let(OreRoundResult::Resolved) ?: OreRoundResult.Unavailable
  }

  private suspend fun board(): OreBoard =
    OreBoard.decode(owned(checkNotNull(rpc.account(BOARD)) { "ORE board is unavailable" }))

  private fun owned(account: SolanaRpc.Account): ByteArray {
    check(account.owner == PROGRAM_ID) { "Account is not owned by ORE" }
    return account.data
  }

  private suspend fun verifyCluster() {
    if (verified) return
    check(rpc.genesisHash() == MAINNET_GENESIS) { "ORE results require Solana mainnet" }
    verified = true
  }

  companion object {
    const val RPC_URL = "https://api.mainnet-beta.solana.com"
    const val MAINNET_GENESIS = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"
    val PROGRAM_ID = SolanaPublicKey.from("oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv")
    val BOARD = SolanaPublicKey.from("BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi")

    // ORE Round::rng / winning_square: XOR four little-endian u64 limbs, modulo 25.
    internal fun winningNumber(entropy: ByteArray): Int? {
      require(entropy.size == 32)
      if (entropy.all { it == 0.toByte() } || entropy.all { it == (-1).toByte() }) return null
      var rng = 0uL
      repeat(4) { limb ->
        var value = 0uL
        repeat(8) { byte ->
          value = value or ((entropy[limb * 8 + byte].toUByte().toULong()) shl (byte * 8))
        }
        rng = rng xor value
      }
      return (rng % 25u).toInt() + 1
    }
  }
}

sealed interface OreRoundResult {
  data object Pending : OreRoundResult

  data object Unavailable : OreRoundResult

  data class Resolved(val number: Int) : OreRoundResult
}
