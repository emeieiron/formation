package xyz.mcxross.formation.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.crypto.secureRandomBytes
import xyz.mcxross.formation.platform.PlatformServices
import xyz.mcxross.formation.session.FormationJson
import xyz.mcxross.formation.session.PlayerIdentity

@Serializable data class Profile(val name: String, val light: Int)

class Identity(private val platform: PlatformServices) {
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

  val claimKey: Ed25519KeyPair by lazy {
    platform.secrets.get(SECRET_CLAIM)?.let(Ed25519KeyPair::fromSeed)
      ?: Ed25519KeyPair.generate().also { platform.secrets.put(SECRET_CLAIM, it.seed) }
  }

  val claimAddress: String
    get() = Base58.encode(claimKey.publicKey)

  fun save(profile: Profile) {
    platform.store.put(KEY_PROFILE, FormationJson.encodeToString(Profile.serializer(), profile))
    _profile.value = profile
  }

  fun player(): PlayerIdentity {
    val p = profile.value ?: Profile("Player", 0)
    return PlayerIdentity(device, p.name, p.light, claimKey, wallet.value,
      ChallengeCatalog.all.associate { it.id.value to it.formatVersion },
      platform.motion.available.map { it.name }.toSet())
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
