package xyz.mcxross.formation.ricochet

import kotlin.math.pow
import kotlinx.serialization.Serializable

@Serializable
data class Momentum(val exchanges: Int = 0, val lastSide: Int? = null) {
  val factor: Double get() = 1.08.pow(exchanges.coerceAtMost(6)).coerceAtMost(MAX_FACTOR)

  fun returned(side: Int) = copy(
    exchanges = exchanges + if (lastSide != null && lastSide != side) 1 else 0,
    lastSide = side,
  )

  companion object {
    const val MAX_FACTOR = 1.5
  }
}
