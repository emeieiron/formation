package xyz.mcxross.formation.model

import kotlinx.serialization.Serializable

// Same integer arithmetic as the vault program: the owner takes [ownerBps] plus the rounding dust.
@Serializable
data class RewardSplit(val total: Skr, val owner: Skr, val helper: Skr, val helpers: Int) {
  init {
    require(owner.units + helper.units * helpers == total.units) { "Split does not add up" }
  }

  companion object {
    const val BPS = 10_000

    fun of(total: Skr, ownerBps: Int, helpers: Int): RewardSplit {
      require(total.units >= 0) { "Negative reward" }
      require(ownerBps in 0 until BPS) { "The owner share must leave something for the helpers" }
      require(helpers >= 1) { "A Formation needs at least one helper" }
      val ownerCut = mulDivBps(total.units, ownerBps)
      val pool = total.units - ownerCut
      val share = pool / helpers
      val dust = pool - share * helpers
      return RewardSplit(total, Skr(ownerCut + dust), Skr(share), helpers)
    }

    /** floor(amount * bps / 10 000) without overflowing on large amounts. */
    internal fun mulDivBps(amount: Long, bps: Int): Long =
      (amount / BPS) * bps + (amount % BPS) * bps / BPS
  }
}
