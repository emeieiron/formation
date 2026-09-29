package xyz.mcxross.formation.link

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

class FixedHostFinder(addresses: Set<HostAddress>) : HostFinder {
  override val candidates: Flow<Set<HostAddress>> = flowOf(addresses)
}

class CombinedHostFinder(private val finders: List<HostFinder>) : HostFinder {
  override val candidates: Flow<Set<HostAddress>> =
    if (finders.isEmpty()) flowOf(emptySet())
    else combine(finders.map { it.candidates }) { sets -> sets.flatMap { it }.toSet() }
}

object EmulatorBridge {
  /**
   * With `scripts/emulators.sh link`, emulator i's port 47000 is forwarded to the development
   * machine's port 47000 + i, which every emulator reaches through 10.0.2.2.
   */
  fun candidates(count: Int = 10): Set<HostAddress> =
    (0 until count).map { HostAddress(LinkDefaults.EMULATOR_HOST, LinkDefaults.PORT + it) }.toSet()
}
