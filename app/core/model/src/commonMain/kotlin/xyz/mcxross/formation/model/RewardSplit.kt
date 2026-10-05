package xyz.mcxross.formation.model

import kotlinx.serialization.Serializable

// Same integer arithmetic as the vault program: the owner gets [ownerWeight] times each helper's share,
// plus the rounding dust.
@Serializable
data class RewardSplit(val total: Skr, val owner: Skr, val helper: Skr, val helpers: Int) {
  init {
    require(owner.units + helper.units * helpers == total.units) { "Split does not add up" }
  }

  companion object {
    fun of(total: Skr, ownerWeight: Int, helpers: Int): RewardSplit {
      require(total.units >= 0) { "Negative reward" }
      require(ownerWeight >= 1) { "The owner weight must be at least 1" }
      require(helpers >= 1) { "A Formation needs at least one helper" }
      val share = total.units / (ownerWeight + helpers)
      return RewardSplit(total, Skr(total.units - share * helpers), Skr(share), helpers)
    }
  }
}
