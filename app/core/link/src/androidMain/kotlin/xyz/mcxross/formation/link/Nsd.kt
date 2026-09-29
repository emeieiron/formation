package xyz.mcxross.formation.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

class NsdAdvertiser(context: Context) : Advertiser {
  private val nsd = context.getSystemService(NsdManager::class.java)
  private var registration: NsdManager.RegistrationListener? = null

  override fun advertise(name: String, port: Int) {
    stop()
    val info =
      NsdServiceInfo().apply {
        serviceName = name
        serviceType = LinkDefaults.SERVICE_TYPE
        this.port = port
      }
    val listener =
      object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {}

        override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {}

        override fun onServiceUnregistered(info: NsdServiceInfo) {}

        override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
      }
    registration = listener
    runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
  }

  override fun stop() {
    val listener = registration ?: return
    registration = null
    runCatching { nsd.unregisterService(listener) }
  }
}

class NsdHostFinder(context: Context) : HostFinder {
  private val nsd = context.getSystemService(NsdManager::class.java)

  private sealed interface Event {
    class Found(val info: NsdServiceInfo) : Event

    class Lost(val name: String) : Event
  }

  override val candidates: Flow<Set<HostAddress>> = callbackFlow {
    val events = Channel<Event>(Channel.UNLIMITED)
    val listener =
      object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}

        override fun onDiscoveryStopped(serviceType: String) {}

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

        override fun onServiceFound(info: NsdServiceInfo) {
          events.trySend(Event.Found(info))
        }

        override fun onServiceLost(info: NsdServiceInfo) {
          events.trySend(Event.Lost(info.serviceName))
        }
      }
    // Resolving one service at a time: older NsdManagers reject concurrent resolves.
    launch {
      val found = LinkedHashMap<String, HostAddress>()
      send(emptySet())
      for (event in events) {
        when (event) {
          is Event.Found -> resolve(event.info)?.let { found[event.info.serviceName] = it }
          is Event.Lost -> found.remove(event.name)
        }
        send(found.values.toSet())
      }
    }
    runCatching {
      nsd.discoverServices(LinkDefaults.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }
    awaitClose {
      events.close()
      runCatching { nsd.stopServiceDiscovery(listener) }
    }
  }
    .distinctUntilChanged()

  @Suppress("DEPRECATION")
  private suspend fun resolve(info: NsdServiceInfo): HostAddress? =
    suspendCancellableCoroutine { cont ->
      val listener =
        object : NsdManager.ResolveListener {
          override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
            if (cont.isActive) cont.resume(null)
          }

          override fun onServiceResolved(info: NsdServiceInfo) {
            val host = info.host?.hostAddress
            if (cont.isActive) cont.resume(host?.let { HostAddress(it, info.port) })
          }
        }
      runCatching { nsd.resolveService(info, listener) }
        .onFailure { if (cont.isActive) cont.resume(null) }
    }
}
