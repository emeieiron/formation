package xyz.mcxross.formation.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class NsdAdvertiser(context: Context) : Advertiser {
  private val nsd = context.getSystemService(NsdManager::class.java)
  override val status = MutableStateFlow<DiscoveryStatus>(DiscoveryStatus.Searching)
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
        override fun onServiceRegistered(info: NsdServiceInfo) { if (registration !== this) return; status.value = DiscoveryStatus.Searching }

        override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { if (registration !== this) return; status.value = DiscoveryStatus.Failed(DiscoveryStatus.Reason.START) }

        override fun onServiceUnregistered(info: NsdServiceInfo) {}

        override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) { if (registration !== this) return; status.value = DiscoveryStatus.Failed(DiscoveryStatus.Reason.STOP) }
      }
    registration = listener
    runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
      .onFailure { status.value = DiscoveryStatus.Failed(DiscoveryStatus.Reason.START) }
  }

  override fun stop() {
    val listener = registration ?: return
    registration = null
    runCatching { nsd.unregisterService(listener) }
  }
}

class NsdHostFinder(context: Context) : HostFinder {
  override val status = MutableStateFlow<DiscoveryStatus>(DiscoveryStatus.Searching)
  private val nsd = context.getSystemService(NsdManager::class.java)

  private val generation = AtomicLong()

  private sealed interface Event {
    class Found(val info: NsdServiceInfo) : Event

    class Lost(val name: String) : Event
  }

  override val candidates: Flow<Set<HostAddress>> = callbackFlow {
    val request = generation.incrementAndGet()
    val closing = AtomicBoolean(false)
    fun report(next: DiscoveryStatus) {
      if (request == generation.get() && !closing.get()) status.value = next
    }
    report(DiscoveryStatus.Searching)
    val events = Channel<Event>(64, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val listener =
      object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) { report(DiscoveryStatus.Searching) }

        override fun onDiscoveryStopped(serviceType: String) { report(DiscoveryStatus.Failed(DiscoveryStatus.Reason.STOP)) }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { report(DiscoveryStatus.Failed(DiscoveryStatus.Reason.START)) }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { report(DiscoveryStatus.Failed(DiscoveryStatus.Reason.STOP)) }

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
          is Event.Found -> resolve(event.info, ::report)?.let { found[event.info.serviceName] = it }
          is Event.Lost -> found.remove(event.name)
        }
        send(found.values.toSet())
      }
    }
    runCatching {
      nsd.discoverServices(LinkDefaults.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }.onFailure { report(DiscoveryStatus.Failed(DiscoveryStatus.Reason.START)) }
    awaitClose {
      closing.set(true)
      events.close()
      runCatching { nsd.stopServiceDiscovery(listener) }
    }
  }
    .distinctUntilChanged()

  @Suppress("DEPRECATION")
  private suspend fun resolve(info: NsdServiceInfo, report: (DiscoveryStatus) -> Unit): HostAddress? =
    withTimeoutOrNull(5_000) { suspendCancellableCoroutine { cont ->
      val listener =
        object : NsdManager.ResolveListener {
          override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
            if (cont.isActive) { report(DiscoveryStatus.Failed(DiscoveryStatus.Reason.RESOLVE)); cont.resume(null) }
          }

          override fun onServiceResolved(info: NsdServiceInfo) {
            val host = info.host?.hostAddress
            if (cont.isActive) { report(DiscoveryStatus.Searching); cont.resume(host?.let { HostAddress(it, info.port) }) }
          }
        }
      cont.invokeOnCancellation {
        if (android.os.Build.VERSION.SDK_INT >= 34) runCatching { nsd.stopServiceResolution(listener) }
      }
      runCatching { nsd.resolveService(info, listener) }
        .onFailure { if (cont.isActive) { report(DiscoveryStatus.Failed(DiscoveryStatus.Reason.RESOLVE)); cont.resume(null) } }
    } } ?: run {
      if (status.value !is DiscoveryStatus.Failed) report(DiscoveryStatus.Failed(DiscoveryStatus.Reason.TIMEOUT))
      null
    }
}
