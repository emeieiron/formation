package xyz.mcxross.formation.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import xyz.mcxross.formation.BuildConfig
import xyz.mcxross.formation.link.CombinedHostFinder
import xyz.mcxross.formation.link.EmulatorBridge
import xyz.mcxross.formation.link.FixedHostFinder
import xyz.mcxross.formation.link.KtorLinkServer
import xyz.mcxross.formation.link.LocalNetwork as Addresses
import xyz.mcxross.formation.link.NsdAdvertiser
import xyz.mcxross.formation.link.NsdHostFinder
import xyz.mcxross.formation.link.linkHttpClient
import xyz.mcxross.formation.sensors.AndroidHaptics
import xyz.mcxross.formation.sensors.AndroidMotion
import xyz.mcxross.formation.wallet.MwaWallet

interface ActivityBridge {
  val walletSender: ActivityResultSender

  fun keepScreenOn(on: Boolean)

  suspend fun requestPermissions(permissions: Array<String>): Map<String, Boolean>
}

class AndroidPlatform(private val context: Context) : PlatformServices {
  @Volatile var bridge: ActivityBridge? = null

  override val config =
    AppConfig(
      version = BuildConfig.VERSION_NAME,
      debug = BuildConfig.DEBUG,
      rpcUrl = BuildConfig.SOLANA_RPC_URL,
      cluster = BuildConfig.SOLANA_CLUSTER,
    )

  override val store =
    object : KeyValueStore {
      private val prefs = context.getSharedPreferences("formation", Context.MODE_PRIVATE)

      override fun get(key: String): String? = prefs.getString(key, null)

      override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
      }
    }

  override val secrets: SecretStore = KeystoreSecrets(context)

  override val device =
    DeviceInfo(
      model = "${Build.MANUFACTURER} ${Build.MODEL}",
      emulator = isEmulator(),
      seeker =
        Build.MODEL.equals("Seeker", ignoreCase = true) &&
          Build.MANUFACTURER.contains("Solana", ignoreCase = true),
    )

  override val motion = AndroidMotion(context)

  override val haptics = AndroidHaptics(context)

  override val network =
    object : LocalNetwork {
      override val server = KtorLinkServer()
      override val advertiser = NsdAdvertiser(context)
      override val finder =
        CombinedHostFinder(
          listOfNotNull(
            NsdHostFinder(context),
            // Emulators sit behind their own NAT; the bridge reaches the others through the host.
            FixedHostFinder(EmulatorBridge.candidates()).takeIf { device.emulator },
          )
        )
      override val http = linkHttpClient()

      override fun addresses(): List<String> = Addresses.addresses()
    }

  override val wallet: WalletPort =
    MwaWallet(context, cluster = config.cluster, secrets = secrets) { bridge?.walletSender }

  override val external =
    object : ExternalPort {
      private fun launch(intent: Intent): Boolean =
        try {
          context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          true
        } catch (_: ActivityNotFoundException) {
          false
        }

      override fun share(text: String) {
        val send =
          Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        launch(Intent.createChooser(send, "Invite to your Formation"))
      }

      override fun openUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme == "https") launch(Intent(Intent.ACTION_VIEW, uri))
      }

      override suspend fun scanQr(): String? {
        val options =
          GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        val scanner = GmsBarcodeScanning.getClient(context, options)
        return suspendCancellableCoroutine { cont ->
          scanner
            .startScan()
            .addOnSuccessListener { if (cont.isActive) cont.resume(it.rawValue) }
            .addOnCanceledListener { if (cont.isActive) cont.resume(null) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
      }

      override fun keepScreenOn(on: Boolean) {
        bridge?.keepScreenOn(on)
      }
    }

  override val hotspot: HotspotPort = AndroidHotspot(context) { bridge }

  private fun isEmulator(): Boolean =
    Build.HARDWARE in setOf("goldfish", "ranchu") ||
      Build.FINGERPRINT.startsWith("generic") ||
      Build.PRODUCT.startsWith("sdk_") ||
      Build.MODEL.contains("sdk_gphone")
}
