package xyz.mcxross.formation.platform

import io.ktor.client.HttpClient
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import xyz.mcxross.formation.crypto.Base64
import xyz.mcxross.formation.link.FixedHostFinder
import xyz.mcxross.formation.link.HostFinder
import xyz.mcxross.formation.link.linkHttpClient
import xyz.mcxross.formation.sensors.Haptics
import xyz.mcxross.formation.sensors.UnsupportedSensorBackend

class IosPlatform : PlatformServices {
  private val defaults = NSUserDefaults.standardUserDefaults

  override val config =
    AppConfig(
      version = "0.1.0",
      developer = true,
      rpcUrl = "https://api.devnet.solana.com",
      cluster = "devnet",
    )

  override val store =
    object : KeyValueStore {
      override fun get(key: String): String? = defaults.stringForKey(key)

      override fun put(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, key)
      }
    }

  // TODO: move to the Keychain before claim keys guard real rewards on iOS.
  override val secrets =
    object : SecretStore {
      override fun get(name: String): ByteArray? =
        defaults.stringForKey("secret.$name")?.let(Base64::decode)

      override fun put(name: String, value: ByteArray) =
        defaults.setObject(Base64.encode(value), "secret.$name")
    }

  override val device =
    DeviceInfo(
      UIDevice.currentDevice.model,
      emulator = UIDevice.currentDevice.name.contains("Simulator"),
    )

  override val sensorBackend = UnsupportedSensorBackend()

  override val sound: SoundPlayer = IosSoundPlayer()

  override val haptics: Haptics =
    object : Haptics {
      override fun tick() =
        UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight).impactOccurred()

      override fun confirm() =
        UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
          .impactOccurred()

      override fun reject() =
        UINotificationFeedbackGenerator()
          .notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeError)

      override fun heavy() =
        UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy).impactOccurred()
    }

  override val network =
    object : LocalNetwork {
      override val server = null
      override val advertiser = null
      override val finder: HostFinder = FixedHostFinder(emptySet())
      override val http: HttpClient = linkHttpClient()

      override fun addresses(): List<String> = emptyList()
    }

  override val wallet =
    object : WalletPort {
      override fun installed() = false

      override suspend fun connect(): WalletResult<WalletAccount> = WalletResult.NoWallet

      override suspend fun signIn(message: ByteArray): WalletResult<SignedMessage> = WalletResult.NoWallet

      override suspend fun signAll(transactions: List<ByteArray>): WalletResult<List<ByteArray>> =
        WalletResult.NoWallet
    }

  override val external =
    object : ExternalPort {
      override fun share(text: String) {}

      override fun openUrl(url: String) {
        NSURL.URLWithString(url)?.let {
          UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any>(), null)
        }
      }

      override suspend fun scanQr(): String? = null

      override fun keepScreenOn(on: Boolean) {
        UIApplication.sharedApplication.idleTimerDisabled = on
      }
    }

  override val hotspot: HotspotPort? = null
}
