package xyz.mcxross.formation.ricochet

import kotlinx.serialization.Serializable

@Serializable
data class Charge(val progress: Int = 0, val armed: Boolean = false) {
  fun returned(alternating: Boolean): Charge {
    if (!alternating || armed) return this
    val next = progress + 1
    return Charge(next, next >= EXCHANGES)
  }

  companion object {
    const val EXCHANGES = 2
  }
}
