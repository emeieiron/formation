package xyz.mcxross.formation.model

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class Skr(val units: Long) : Comparable<Skr> {
  operator fun plus(other: Skr) = Skr(units + other.units)

  operator fun minus(other: Skr) = Skr(units - other.units)

  operator fun times(n: Int) = Skr(units * n)

  override fun compareTo(other: Skr) = units.compareTo(other.units)

  fun format(maxDecimals: Int = DECIMALS): String {
    val negative = units < 0
    val abs = if (negative) -units else units
    val whole = abs / ONE
    val frac = (abs % ONE).toString().padStart(DECIMALS, '0').take(maxDecimals).trimEnd('0')
    val grouped = whole.toString().reversed().chunked(3).joinToString(",").reversed()
    return (if (negative) "-" else "") + grouped + if (frac.isEmpty()) "" else ".$frac"
  }

  override fun toString() = "${format()} SKR"

  companion object {
    const val DECIMALS = 6
    const val ONE = 1_000_000L
    val ZERO = Skr(0)

    fun of(whole: Long) = Skr(whole * ONE)
  }
}
