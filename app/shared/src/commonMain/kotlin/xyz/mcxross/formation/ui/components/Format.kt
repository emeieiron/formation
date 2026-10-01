package xyz.mcxross.formation.ui.components

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import xyz.mcxross.formation.resources.Res
import xyz.mcxross.formation.resources.duration_days
import xyz.mcxross.formation.resources.duration_hours
import xyz.mcxross.formation.resources.duration_minutes
import xyz.mcxross.formation.resources.state_reward_expired

@Composable
fun timeLeft(until: Long, now: Long): String {
  val ms = until - now
  return when {
    ms <= 0 -> stringResource(Res.string.state_reward_expired)
    ms >= 2 * 86_400_000L -> stringResource(Res.string.duration_days, ms / 86_400_000L)
    ms >= 3_600_000L -> stringResource(Res.string.duration_hours, ms / 3_600_000L)
    else -> stringResource(Res.string.duration_minutes, (ms / 60_000L).coerceAtLeast(1))
  }
}

fun shortAddress(address: String): String =
  if (address.length <= 12) address else "${address.take(4)}…${address.takeLast(4)}"

fun possessive(name: String): String = if (name.endsWith("s")) "$name'" else "$name's"
