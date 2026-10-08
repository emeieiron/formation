package xyz.mcxross.formation.session

import kotlinx.serialization.Serializable
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Ed25519
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.model.Opportunity

// What a host shows every phone that connects. When it linked, the wallet holding the Genesis Token
// signed
// [authorization], letting this phone's [hostKey] host for it. The host key signs this session, its
// reward
// and [sessionKey]; the session key signs the welcome and every update.
@Serializable
data class HostProof(
  val opportunity: Opportunity,
  val sessionKey: String,
  val wallet: String = "",
  val hostKey: String = "",
  val authorization: String = "",
  val walletSignature: String = "",
  val sessionSignature: String = "",
)

class HostCredentials(val proof: HostProof, val sessionKey: Ed25519KeyPair)

fun interface HostVerifier {
  // Null when [proof] shows a linked wallet stands behind this host for [session]; otherwise why
  // not, for the player.
  suspend fun problem(session: String, proof: HostProof?): String?
}

object HostChecks {
  private const val HOST_KEY = "Host key: "
  private const val NETWORK = "Network: "

  // The text the wallet shows and signs once, at linking. Each line stays on its own so guests can
  // read it back.
  fun authorization(hostKey: String, network: String, issued: String): String =
    "Formation: let this phone host Formations for this wallet. It can't move funds.\n\n" +
      "$HOST_KEY$hostKey\n$NETWORK$network\nIssued: $issued"

  // Covers the reward's full terms, not just its id, so a host can't show guests different ones.
  fun session(session: String, sessionKey: String, opportunity: Opportunity): ByteArray =
    ("formation.host.v2\n$session\n$sessionKey\n" +
        FormationJson.encodeToString(Opportunity.serializer(), opportunity))
      .encodeToByteArray()

  fun update(session: String, message: String): ByteArray =
    "formation.update.v1\n$session\n$message".encodeToByteArray()

  fun credentials(
    session: String,
    opportunity: Opportunity,
    wallet: String,
    hostKey: Ed25519KeyPair,
    authorization: String,
    walletSignature: String,
  ): HostCredentials {
    val key = Ed25519KeyPair.generate()
    val sessionKey = Base58.encode(key.publicKey)
    val signature = Base58.encode(hostKey.sign(session(session, sessionKey, opportunity)))
    return HostCredentials(
      HostProof(
        opportunity,
        sessionKey,
        wallet,
        Base58.encode(hostKey.publicKey),
        authorization,
        walletSignature,
        signature,
      ),
      key,
    )
  }

  fun problem(proof: HostProof?, session: String, network: String): String? {
    proof ?: return "This host didn't show which Seeker it plays for."
    if (key(proof.sessionKey) == null) return "This host's proof is malformed."
    val wallet = key(proof.wallet) ?: return "This host's proof is malformed."
    val hostKey = key(proof.hostKey) ?: return "This host's proof is malformed."
    if (!signed(proof.walletSignature, proof.authorization.encodeToByteArray(), wallet))
      return "The Seeker's wallet didn't authorize this host."
    val lines = proof.authorization.lines()
    if (lines.none { it == HOST_KEY + proof.hostKey })
      return "The Seeker's wallet authorized a different phone."
    if (lines.none { it == NETWORK + network }) return "This host is set up for another network."
    if (
      !signed(
        proof.sessionSignature,
        session(session, proof.sessionKey, proof.opportunity),
        hostKey,
      )
    )
      return "The host's key didn't sign this Formation."
    return null
  }

  private fun key(value: String) = runCatching {
    Base58.decode(value)
  }
    .getOrNull()
    ?.takeIf { it.size == 32 }

  private fun signed(signature: String, message: ByteArray, key: ByteArray) = runCatching {
    Ed25519.verify(Base58.decode(signature), message, key)
  }
    .getOrDefault(false)
}
