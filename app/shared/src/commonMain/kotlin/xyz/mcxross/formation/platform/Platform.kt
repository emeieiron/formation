package xyz.mcxross.formation.platform

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import xyz.mcxross.formation.link.Advertiser
import xyz.mcxross.formation.link.HostFinder
import xyz.mcxross.formation.link.LinkServer
import xyz.mcxross.formation.sensors.Haptics
import xyz.mcxross.formation.sensors.api.SensorBackend
import xyz.mcxross.formation.session.ScreenProfile

interface PlatformServices {
  val config: AppConfig
  val store: KeyValueStore
  val secrets: SecretStore
  val device: DeviceInfo
  val sensorBackend: SensorBackend
  val haptics: Haptics
  val sound: SoundPlayer
  val network: LocalNetwork
  val wallet: WalletPort
  val external: ExternalPort
  val hotspot: HotspotPort?
  val screen: ScreenPort get() = ScreenPort.Unsupported
  val attestation: DeviceAttestation get() = DeviceAttestation.Unsupported
}

// Hardware key attestation: lets other phones check what this phone is and which app is asking.
interface DeviceAttestation {
  val packageName: String

  // SHA-256 digests (lowercase hex) of the certificates this app is signed with; empty when unknown.
  val signers: Set<String>

  // Makes a key in secure hardware and returns its attestation chain, leaf first, carrying [challenge]
  // and the phone's brand, manufacturer and model. Throws when the phone can't attest them.
  suspend fun attest(challenge: ByteArray): List<ByteArray>

  companion object {
    val Unsupported = object : DeviceAttestation {
      override val packageName = "xyz.mcxross.formation"
      override val signers = emptySet<String>()

      override suspend fun attest(challenge: ByteArray): List<ByteArray> =
        throw UnsupportedOperationException("This phone can't attest its hardware.")
    }
  }
}

sealed interface ScreenMeasurement {
  data class Measured(val profile: ScreenProfile) : ScreenMeasurement

  // The reported density looks wrong; matching a card once fixes it.
  data class NeedsCalibration(val estimate: ScreenProfile?) : ScreenMeasurement

  // Split-screen and multi-window modes shrink the window below the display.
  data object NotFullScreen : ScreenMeasurement

  data object Unsupported : ScreenMeasurement
}

interface ScreenPort {
  // The platform can measure its physical screen, given a calibration if needed.
  val measurable: Boolean
  val measurement: StateFlow<ScreenMeasurement>

  // Stores pixels per millimetre measured against a card; null returns to the platform's value.
  fun calibrate(pxPerMm: Double?)

  // Each true must be matched by a false; the screen leaves full screen when the last holder releases it.
  fun fullScreen(on: Boolean)

  companion object {
    val Unsupported = object : ScreenPort {
      override val measurable = false
      override val measurement: StateFlow<ScreenMeasurement> = MutableStateFlow(ScreenMeasurement.Unsupported)

      override fun calibrate(pxPerMm: Double?) {}

      override fun fullScreen(on: Boolean) {}
    }
  }
}

data class AppConfig(
  val version: String,
  // The developer path: pretend Seekers, simulated rewards and test tools. Never set in a release build.
  val developer: Boolean,
  val rpcUrl: String,
  val cluster: String,
)

data class DeviceInfo(val model: String, val emulator: Boolean, val seeker: Boolean = false)

interface KeyValueStore {
  fun get(key: String): String?

  fun put(key: String, value: String?)

  // Complete persistence before acknowledging a financial or recovery record.
  fun putDurable(key: String, value: String?) = put(key, value)
}

interface SecretStore {
  fun contains(name: String): Boolean = get(name) != null
  fun remove(name: String) { error("Secret removal is unavailable") }

  fun get(name: String): ByteArray?

  fun put(name: String, value: ByteArray)

  // An independently encrypted, device-bound copy. Callers must validate identity
  // before using it to repair the primary record. Unsupported platforms return null.
  fun recoveryCopy(name: String): ByteArray? = null
  fun protect(name: String, value: ByteArray): Boolean = false
}

interface LocalNetwork {
  val server: LinkServer?
  val advertiser: Advertiser?
  val finder: HostFinder
  val http: HttpClient

  fun addresses(): List<String>
}

data class WalletAccount(val address: String, val label: String?)

class SignedMessage(val address: String, val signature: ByteArray)

sealed interface WalletResult<out T> {
  fun <R> map(f: (T) -> R): WalletResult<R> =
    when (this) {
      is Ok -> Ok(f(value))
      NoWallet -> NoWallet
      is Failed -> this
    }

  data class Ok<T>(val value: T) : WalletResult<T>

  data object NoWallet : WalletResult<Nothing>

  data class Failed(val message: String) : WalletResult<Nothing>
}

interface WalletPort {
  fun installed(): Boolean

  suspend fun connect(): WalletResult<WalletAccount>

  // Connects and has the wallet sign [message] with the account it names, proving it holds that key.
  suspend fun signIn(message: ByteArray): WalletResult<SignedMessage>

  // Sign only: the app sends the transaction itself.
  suspend fun sign(transaction: ByteArray): WalletResult<ByteArray> =
    signAll(listOf(transaction)).map { it.single() }

  suspend fun signAll(transactions: List<ByteArray>): WalletResult<List<ByteArray>>
}

interface ExternalPort {
  fun share(text: String)

  fun openUrl(url: String)

  suspend fun scanQr(): String?

  fun keepScreenOn(on: Boolean)

  fun prepareScanner() {}
}

data class HotspotInfo(val ssid: String, val passphrase: String?)

interface HotspotPort {
  val active: kotlinx.coroutines.flow.Flow<HotspotInfo?> get() = kotlinx.coroutines.flow.flowOf(null)

  fun permitted(): Boolean

  suspend fun start(): Result<HotspotInfo>

  fun stop()
}
