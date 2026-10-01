package xyz.mcxross.formation.platform

import io.ktor.client.HttpClient
import xyz.mcxross.formation.link.Advertiser
import xyz.mcxross.formation.link.HostFinder
import xyz.mcxross.formation.link.LinkServer
import xyz.mcxross.formation.sensors.Haptics
import xyz.mcxross.formation.sensors.Motion

interface PlatformServices {
  val config: AppConfig
  val store: KeyValueStore
  val secrets: SecretStore
  val device: DeviceInfo
  val motion: Motion
  val haptics: Haptics
  val sound: SoundPlayer
  val network: LocalNetwork
  val wallet: WalletPort
  val external: ExternalPort
  val hotspot: HotspotPort?
}

data class AppConfig(
  val version: String,
  val debug: Boolean,
  val rpcUrl: String,
  val cluster: String,
  val sgtRpcUrl: String = "https://api.mainnet-beta.solana.com",
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
