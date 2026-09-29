package xyz.mcxross.formation.platform

import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

internal class AndroidHotspot(context: Context, private val bridge: () -> ActivityBridge?) :
  HotspotPort {
  private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
  private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

  override suspend fun start(): Result<HotspotInfo> {
    val permission =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.NEARBY_WIFI_DEVICES
      else Manifest.permission.ACCESS_FINE_LOCATION
    val granted = bridge()?.requestPermissions(arrayOf(permission))?.get(permission) == true
    if (!granted)
      return Result.failure(
        IllegalStateException("The Seeker network needs permission to use nearby Wi-Fi.")
      )
    stop()
    return suspendCancellableCoroutine { cont ->
      val callback =
        object : WifiManager.LocalOnlyHotspotCallback() {
          override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
            reservation = res
            if (cont.isActive) cont.resume(Result.success(describe(res)))
          }

          override fun onFailed(reason: Int) {
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
    reservation?.close()
    reservation = null
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
