package xyz.mcxross.formation.solana

import com.solana.publickey.SolanaPublicKey

object SeekerGenesis {
  val TOKEN_2022 = SolanaPublicKey.from("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
  val MAINNET_GROUP = SolanaPublicKey.from("GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te")

  private const val TOKEN_GROUP_MEMBER = 23
  private const val ACCOUNT_TYPE_OFFSET = 165
  private const val ACCOUNT_TYPE_MINT = 1

  // Token-2022 pads an extended mint to 165 bytes, then an account-type byte, then TLV entries:
  // type u16, length u16, value. The group member value is mint (32), group (32), number (u64).
  fun groupOf(mint: SolanaPublicKey, data: ByteArray): SolanaPublicKey? {
    if (data.size <= ACCOUNT_TYPE_OFFSET || data[ACCOUNT_TYPE_OFFSET].toInt() != ACCOUNT_TYPE_MINT)
      return null
    var at = ACCOUNT_TYPE_OFFSET + 1
    while (at + 4 <= data.size) {
      val kind = data.u16At(at)
      val length = data.u16At(at + 2)
      val start = at + 4
      if (start + length > data.size) return null
      if (kind == TOKEN_GROUP_MEMBER && length >= 64) {
        val member = SolanaPublicKey(data.copyOfRange(start, start + 32))
        // The entry must name this mint, or another mint's membership could be borrowed.
        return if (member == mint) SolanaPublicKey(data.copyOfRange(start + 32, start + 64))
        else null
      }
      if (kind == 0) return null
      at = start + length
    }
    return null
  }

  fun isMember(
    mint: SolanaPublicKey,
    owner: SolanaPublicKey,
    data: ByteArray,
    group: SolanaPublicKey,
  ): Boolean = owner == TOKEN_2022 && groupOf(mint, data) == group
}

data class TokenAccount(val mint: SolanaPublicKey, val owner: SolanaPublicKey, val amount: ULong) {
  companion object {
    const val SIZE = 165

    fun decode(data: ByteArray): TokenAccount? =
      if (data.size < SIZE) null
      else
        TokenAccount(
          SolanaPublicKey(data.copyOfRange(0, 32)),
          SolanaPublicKey(data.copyOfRange(32, 64)),
          data.u64At(64),
        )
  }
}

class SgtFinder(
  private val rpc: SolanaRpc,
  private val group: SolanaPublicKey = SeekerGenesis.MAINNET_GROUP,
) {
  class Found(val mint: SolanaPublicKey, val account: SolanaPublicKey)

  suspend fun find(wallet: SolanaPublicKey): Found? {
    val held =
      rpc.tokenAccountsByOwner(wallet, SeekerGenesis.TOKEN_2022).mapNotNull { (address, data) ->
        TokenAccount.decode(data)
          ?.takeIf { it.owner == wallet && it.amount == 1uL }
          ?.let { address to it.mint }
      }
    if (held.isEmpty()) return null
    val mints = rpc.multipleAccounts(held.map { it.second })
    return held.zip(mints).firstNotNullOfOrNull { (holding, mint) ->
      mint
        ?.takeIf { SeekerGenesis.isMember(holding.second, it.owner, it.data, group) }
        ?.let { Found(holding.second, holding.first) }
    }
  }
}
