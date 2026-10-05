package xyz.mcxross.formation.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.crypto.secureRandomBytes
import xyz.mcxross.formation.crypto.toHex
import xyz.mcxross.formation.platform.PlatformServices
import xyz.mcxross.formation.session.FormationJson
import xyz.mcxross.formation.session.PlayerIdentity
import xyz.mcxross.formation.state.recovery.ClaimIdentity

@Serializable data class Profile(val name: String, val light: Int)

class Identity(private val platform: PlatformServices, private val capabilities: () -> Set<String>) {
  private val _profile = MutableStateFlow(load())
  val profile: StateFlow<Profile?> = _profile.asStateFlow()

  private val _wallet = MutableStateFlow(platform.store.get(KEY_WALLET))
  val wallet: StateFlow<String?> = _wallet.asStateFlow()

  fun saveWallet(address: String?) {
    platform.store.put(KEY_WALLET, address)
    _wallet.value = address
  }

  val device: String =
    platform.store.get(KEY_DEVICE) ?: newUuid().also { platform.store.put(KEY_DEVICE, it) }

  val claims = ClaimIdentity(platform.store, platform.secrets) {
    loadList(platform.store, "sessions.completed", CompletedSession.serializer())
      .flatMap { it.snapshot.players }.firstOrNull { it.device == device }?.claimKey
  }
  val claimKey: Ed25519KeyPair get() = claims.key
  val claimAddress: String get() = claims.address ?: "Claim key unavailable"

  fun save(profile: Profile) {
    platform.store.put(KEY_PROFILE, FormationJson.encodeToString(Profile.serializer(), profile))
    _profile.value = profile
  }

  fun player(): PlayerIdentity {
    val p = profile.value ?: Profile("Player", 0)
    return PlayerIdentity(device, p.name, p.light, claimKey, wallet.value,
      ChallengeCatalog.formats,
      capabilities())
  }

  private fun load(): Profile? =
    platform.store.get(KEY_PROFILE)?.let {
      runCatching { FormationJson.decodeFromString(Profile.serializer(), it) }.getOrNull()
    }

  private companion object {
    const val KEY_PROFILE = "profile"
    const val KEY_DEVICE = "device"
    const val KEY_WALLET = "wallet"
    const val SECRET_CLAIM = "claim-key"
  }
}

fun newUuid(): String {
  val b = secureRandomBytes(16)
  b[6] = (b[6].toInt() and 0x0f or 0x40).toByte()
  b[8] = (b[8].toInt() and 0x3f or 0x80).toByte()
  return uuidOf(b)
}

private fun uuidOf(bytes: ByteArray): String {
  val hex = bytes.toHex()
  return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}
