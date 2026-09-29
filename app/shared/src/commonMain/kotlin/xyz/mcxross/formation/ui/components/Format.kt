package xyz.mcxross.formation.ui.components

fun timeLeft(until: Long, now: Long): String {
  val ms = until - now
  return when {
    ms <= 0 -> "Expired"
    ms >= 2 * 86_400_000L -> "${ms / 86_400_000L} days left"
    ms >= 3_600_000L -> "${ms / 3_600_000L} h left"
    else -> "${(ms / 60_000L).coerceAtLeast(1)} min left"
  }
}

fun shortAddress(address: String): String =
  if (address.length <= 12) address else "${address.take(4)}…${address.takeLast(4)}"

fun possessive(name: String): String = if (name.endsWith("s")) "$name'" else "$name's"
