package xyz.mcxross.formation.solana.ore

import com.solana.publickey.SolanaPublicKey

data class OreBoard(
  val roundId: ULong,
  val startSlot: ULong,
  val endSlot: ULong,
  val productionCostEma: ULong,
) {
  val waitingForDeployment: Boolean
    get() = endSlot == ULong.MAX_VALUE

  companion object {
    fun decode(data: ByteArray): OreBoard =
      SteelReader(data, 105, 40).run {
        OreBoard(u64(), u64(), u64(), u64())
      }
  }
}

data class OreAdminConfig(
  val authority: SolanaPublicKey,
  val feeCollector: SolanaPublicKey,
  val feeRate: ULong,
)

data class OreProtocolConfig(
  val authority: SolanaPublicKey,
  val feeCollector: SolanaPublicKey,
  val feeRate: ULong,
  val intermissionSlots: ULong,
  val roundSlots: ULong,
  val entropyVar: SolanaPublicKey,
  val entropyProgramId: SolanaPublicKey,
)

data class OreConfig(val admin: OreAdminConfig, val protocol: OreProtocolConfig) {
  companion object {
    fun decode(data: ByteArray): OreConfig =
      SteelReader(data, 101, 232).run {
        OreConfig(
          OreAdminConfig(key(), key(), u64()),
          OreProtocolConfig(key(), key(), u64(), u64(), u64(), key(), key()),
        )
      }
  }
}

data class OreMiner(
  val authority: SolanaPublicKey,
  val autoReturn: ULong,
  val checkpointId: ULong,
  val checkpointFee: ULong,
  val deployed: List<ULong>,
  val mass: List<ULong>,
  val cumulative: List<ULong>,
  val roundId: ULong,
  val rewardsFactor: OreNumeric,
  val rewardsSol: ULong,
  val refinedOre: ULong,
  val rewardsOre: ULong,
  val lastClaimOreAt: Long,
  val lastClaimSolAt: Long,
  val lifetimeRewardsOre: ULong,
  val lifetimeDeployed: ULong,
  val lifetimeRewardsSol: ULong,
) {
  companion object {
    fun decode(data: ByteArray): OreMiner =
      SteelReader(data, 103, 752).run {
        OreMiner(
          key(),
          u64(),
          u64(),
          u64(),
          squares(),
          squares(),
          squares(),
          u64(),
          numeric(),
          u64(),
          u64(),
          u64(),
          i64(),
          i64(),
          u64(),
          u64(),
          u64(),
        )
      }
  }
}

class OreRound(
  val id: ULong,
  val deployed: List<ULong>,
  val mass: List<ULong>,
  val count: List<ULong>,
  val entropy: ByteArray,
  val expiresAt: ULong,
  val motherlode: ULong,
  val rentPayer: SolanaPublicKey,
  val rewards: List<ULong>,
  val totalVaulted: ULong,
  val totalReturnedSol: ULong,
  val totalMiners: ULong,
  val topMiner: SolanaPublicKey,
) {
  companion object {
    fun decode(data: ByteArray): OreRound =
      SteelReader(data, 109, 952).run {
        OreRound(
          u64(),
          squares(),
          squares(),
          squares(),
          bytes(32),
          u64(),
          u64(),
          key(),
          squares(),
          u64(),
          u64(),
          u64(),
          key(),
        )
      }
  }
}

data class OreTreasury(
  val motherlode: ULong,
  val minerRewardsFactor: OreNumeric,
  val totalRefined: ULong,
  val totalUnclaimed: ULong,
) {
  companion object {
    fun decode(data: ByteArray): OreTreasury =
      SteelReader(data, 104, 48).run {
        OreTreasury(u64(), numeric(), u64(), u64())
      }
  }
}
