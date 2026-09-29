package xyz.mcxross.formation.link

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalNetwork {
  fun addresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces()
      .toList()
      .filter { it.isUp && !it.isLoopback && !it.isVirtual }
      .flatMap { nif ->
        nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif to it }
      }
      .sortedBy { (nif, address) ->
        when {
          nif.name.startsWith("wlan") ||
            nif.name.startsWith("ap") ||
            nif.name.startsWith("swlan") -> 0
          address.isSiteLocalAddress -> 1
          else -> 2
        }
      }
      .mapNotNull { it.second.hostAddress }
  }
    .getOrDefault(emptyList())
}
