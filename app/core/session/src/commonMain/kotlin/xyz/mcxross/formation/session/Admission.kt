package xyz.mcxross.formation.session

import kotlinx.serialization.Serializable
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Ed25519
import xyz.mcxross.formation.crypto.secureRandomBytes

@Serializable
data class AdmissionChallenge(
  val session: String,
  val nonce: String,
  val format: String,
  val formatVersion: Int,
  val capabilities: Set<String>,
)

internal fun admissionChallenge(info: FormationInfo, rules: ChallengeRules<*, *>) = AdmissionChallenge(
  info.session, Base58.encode(secureRandomBytes(32)), rules.id.value, rules.formatVersion,
  rules.requiredCapabilities(info.opportunity.players))

// Domain separation and a canonical payload bind every admission field to this connection.
fun admissionMessage(challenge: AdmissionChallenge, hello: ToHost.Hello): ByteArray {
  val canonical = hello.copy(signature = "", formats = hello.formats.entries.sortedBy { it.key }.associate { it.toPair() },
    capabilities = hello.capabilities.sorted().toSet())
  return ("formation.admission.v1\n" + FormationJson.encodeToString(AdmissionChallenge.serializer(),
    challenge.copy(capabilities = challenge.capabilities.sorted().toSet())) + "\n" +
    FormationJson.encodeToString(ToHost.Hello.serializer(), canonical)).encodeToByteArray()
}

internal fun admissionRejection(challenge: AdmissionChallenge, hello: ToHost.Hello): Rejection? {
  if (hello.protocol != PROTOCOL_VERSION) return Rejection.VERSION
  if (hello.formats[challenge.format] != challenge.formatVersion) return Rejection.FORMAT
  if (!hello.capabilities.containsAll(challenge.capabilities)) return Rejection.CAPABILITY
  if (hello.nonce != challenge.nonce || hello.device.length !in 1..128 || hello.name.length > 128) return Rejection.IDENTITY
  val valid = runCatching {
    val key = Base58.decode(hello.claimKey)
    key.size == 32 && Ed25519.verify(Base58.decode(hello.signature), admissionMessage(challenge, hello), key)
  }.getOrDefault(false)
  return if (valid) null else Rejection.IDENTITY
}
