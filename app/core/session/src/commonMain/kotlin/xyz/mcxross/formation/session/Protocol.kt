package xyz.mcxross.formation.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.model.Skr

// Bump whenever phones and Seekers on different versions could misunderstand each other.
const val PROTOCOL_VERSION = 7

val FormationJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
  explicitNulls = false
  classDiscriminator = "t"
}

@Serializable
data class FormationInfo(
  val session: String,
  val code: String,
  val host: String,
  val opportunity: Opportunity,
)

@Serializable
data class Player(
  val id: PlayerId,
  val name: String,
  val light: Int,
  val seeker: Boolean,
  val claimKey: String,
  val wallet: String? = null,
  val connected: Boolean = true,
  val ready: Boolean = false,
  val latencyMs: Int? = null,
  val device: String? = null,
  val clockReady: Boolean = false,
  val capabilities: Set<String> = emptySet(),
  val sensorReady: Boolean = true,
)

@Serializable
sealed interface Stage {
  @Serializable @SerialName("lobby") data object Lobby : Stage

  @Serializable @SerialName("briefing") data class Briefing(val until: Long) : Stage

  @Serializable
  @SerialName("playing")
  data class Playing(val goAt: Long, val roster: List<PlayerId>) : Stage

  @Serializable
  @SerialName("won")
  data class Won(
    val result: RoundResult,
    val seal: Seal,
    val unlock: Unlock,
    val storageProblem: String? = null,
  ) : Stage

  @Serializable @SerialName("lost") data class Lost(val result: RoundResult) : Stage

  @Serializable @SerialName("closed") data class Closed(val reason: String) : Stage
}

@Serializable
data class RoundResult(
  val headline: String,
  val culprit: PlayerId? = null,
  val stats: List<Stat> = emptyList(),
  val endedAt: Long,
)

@Serializable
data class Share(
  val player: PlayerId,
  val claimKey: String,
  val index: Int,
  val amount: Skr,
  val wallet: String? = null,
)

@Serializable
data class Seal(
  val roster: List<Share>,
  val ownerAmount: Skr,
  val root: String,
  val message: String,
  val required: List<PlayerId>,
  val signed: List<PlayerId> = emptyList(),
  val signatures: Map<PlayerId, String> = emptyMap(),
) {
  val complete: Boolean
    get() = signed.containsAll(required)
}

@Serializable
sealed interface Unlock {
  @Serializable @SerialName("waiting") data object Waiting : Unlock

  @Serializable @SerialName("unlocking") data object Unlocking : Unlock

  @Serializable
  @SerialName("unlocked")
  data class Unlocked(
    val receipt: String,
    val at: Long,
    val explorerUrl: String? = null,
    val paid: List<PlayerId> = emptyList(),
    val settled: Boolean = true,
  ) : Unlock

  @Serializable @SerialName("failed") data class Failed(val message: String) : Unlock
}

@Serializable
data class SessionSnapshot(
  val formation: FormationInfo,
  val players: List<Player>,
  val stage: Stage,
  val round: Int,
) {
  fun player(id: PlayerId?): Player? = players.firstOrNull { it.id == id }

  val seeker: Player?
    get() = players.firstOrNull { it.seeker }

  val full: Boolean
    get() = players.size >= formation.opportunity.players
}

@Serializable
sealed interface ToHost {
  @Serializable
  @SerialName("hello")
  data class Hello(
    val protocol: Int,
    val device: String,
    val name: String,
    val light: Int,
    val claimKey: String,
    val wallet: String? = null,
    val formats: Map<String, Int> = emptyMap(),
    val capabilities: Set<String> = emptySet(),
    val nonce: String = "",
    // A fresh nonce the Seeker echoes in its signed welcome.
    val presence: String = "",
    val signature: String = "",
  ) : ToHost

  @Serializable @SerialName("wallet") data class Wallet(val address: String?) : ToHost

  @Serializable @SerialName("profile") data class Profile(val name: String, val light: Int) : ToHost

  @Serializable
  @SerialName("ping")
  data class Ping(val sent: Long, val rtt: Int? = null, val synced: Boolean = false) : ToHost

  @Serializable @SerialName("ready") data class Ready(val ready: Boolean) : ToHost

  @Serializable
  @SerialName("sensors")
  data class Sensors(
    val round: Int,
    val available: Set<String>,
    val screen: ScreenProfile? = null,
  ) : ToHost

  @Serializable @SerialName("play") data class Play(val round: Int, val input: JsonElement) : ToHost

  @Serializable
  @SerialName("seal")
  data class SealIt(val round: Int, val signature: String) : ToHost

  @Serializable @SerialName("leave") data object Leave : ToHost
}

@Serializable
sealed interface ToPlayer {
  @Serializable
  @SerialName("authenticate")
  data class Authenticate(val challenge: AdmissionChallenge, val host: HostProof? = null) : ToPlayer

  @Serializable
  @SerialName("welcome")
  data class Welcome(val you: PlayerId, val presence: String = "") : ToPlayer

  // Another ToPlayer message, exactly as the Seeker signed it with its session key.
  @Serializable
  @SerialName("signed")
  data class Signed(val message: String, val signature: String) : ToPlayer

  @Serializable @SerialName("pong") data class Pong(val sent: Long, val host: Long) : ToPlayer

  @Serializable @SerialName("session") data class Session(val snapshot: SessionSnapshot) : ToPlayer

  // [seq] only grows within a round.
  @Serializable
  @SerialName("frame")
  data class Frame(val round: Int, val seq: Long, val state: JsonElement) : ToPlayer

  @Serializable @SerialName("rejected") data class Rejected(val reason: Rejection) : ToPlayer
}

enum class Rejection(val message: String) {
  IDENTITY("This phone could not prove its player identity. Restore its claim key and retry."),
  FORMAT("This phone does not support the Formation's challenge format. Update both phones."),
  CAPABILITY("This phone is missing a sensor or screen measurement required for this Formation."),
  FULL("This Formation is already full."),
  STARTED("This Formation has already started."),
  VERSION("This Formation runs a different version of the app. Update both phones."),
  DUPLICATE("This phone is already in the Formation."),
  CLOSED("This Formation has ended."),
}

@Serializable
data class Beacon(
  val protocol: Int,
  val session: String,
  val code: String,
  val host: String,
  val challenge: ChallengeId,
  val reward: Skr,
  val players: Int,
  val joined: Int,
  val open: Boolean,
  val helperShare: Skr,
  val tier: String,
)
