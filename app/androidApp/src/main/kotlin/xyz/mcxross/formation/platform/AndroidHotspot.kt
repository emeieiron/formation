package xyz.mcxross.formation.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow

internal class AndroidHotspot(context: Context, private val bridge: () -> ActivityBridge?) :
  HotspotPort {
  private val appContext = context.applicationContext
  private val wifi = appContext.getSystemService(WifiManager::class.java)
  override val active = MutableStateFlow<HotspotInfo?>(null)
  private val lock = Any()
  private var generation = 0L
  private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

  private val permission =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
      Manifest.permission.NEARBY_WIFI_DEVICES
    else Manifest.permission.ACCESS_FINE_LOCATION

  override fun permitted(): Boolean =
    ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

  override suspend fun start(): Result<HotspotInfo> {
    val granted = bridge()?.requestPermissions(arrayOf(permission))?.get(permission) == true
    if (!granted)
      return Result.failure(
        IllegalStateException("The Seeker network needs permission to use nearby Wi-Fi.")
      )
    stop()
    val request = synchronized(lock) { ++generation }
    return suspendCancellableCoroutine { cont ->
      cont.invokeOnCancellation { stopRequest(request) }
      val callback =
        object : WifiManager.LocalOnlyHotspotCallback() {
          override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
            val accepted = synchronized(lock) {
              if (request != generation || !cont.isActive) false
              else { reservation = res; active.value = describe(res); true }
            }
            if (!accepted) res.close()
            else if (cont.isActive) cont.resume(Result.success(describe(res)))
          }

          override fun onStopped() { stopRequest(request) }

          override fun onFailed(reason: Int) {
            stopRequest(request)
            val message =
              when (reason) {
                ERROR_TETHERING_DISALLOWED -> "This phone doesn't allow hotspots."
                ERROR_INCOMPATIBLE_MODE -> "Turn off the regular hotspot first."
                ERROR_NO_CHANNEL -> "No free Wi-Fi channel right now."
                else -> "The Seeker network couldn't start."
              }
            if (cont.isActive) cont.resume(Result.failure(IllegalStateException(message)))
          }
        }
      try {
        wifi.startLocalOnlyHotspot(callback, Handler(Looper.getMainLooper()))
      } catch (e: SecurityException) {
        if (cont.isActive) cont.resume(Result.failure(e))
      } catch (e: IllegalStateException) {
        if (cont.isActive)
          cont.resume(Result.failure(IllegalStateException("A Seeker network is already open.")))
      }
    }
  }

  override fun stop() {
    val previous = synchronized(lock) {
      generation++
      reservation.also { reservation = null; active.value = null }
    }
    previous?.close()
  }

  private fun stopRequest(request: Long) {
    val previous = synchronized(lock) {
      if (generation != request) return
      generation++
      reservation.also { reservation = null; active.value = null }
    }
    previous?.close()
  }

  @Suppress("DEPRECATION")
  private fun describe(res: WifiManager.LocalOnlyHotspotReservation): HotspotInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val config = res.softApConfiguration
      HotspotInfo(config.ssid?.trim('"') ?: "Seeker", config.passphrase)
    } else {
      val config = res.wifiConfiguration
      HotspotInfo(config?.SSID?.trim('"') ?: "Seeker", config?.preSharedKey?.trim('"'))
    }
}
