package xyz.mcxross.formation.session

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId

interface ChallengeRules<S : Any, I : Any> {
  val formatVersion: Int
    get() = 1

  // Wallet approval can temporarily put a participant in another app.
  val reconnectGraceMs: Long?
    get() = null

  // Untimed games can preserve their state when a participant returns within the grace period.
  val resumesAfterReconnect: Boolean
    get() = false

  fun requiredCapabilities(players: Int): Set<String> = emptySet()

  fun optionalCapabilities(players: Int): Set<String> = emptySet()

  fun activeCapabilities(state: S, player: PlayerId, players: Int): Set<String> =
    requiredCapabilities(players)

  // Games drawn at physical scale need every phone's measured screen before the round starts.
  fun screenRequirement(players: Int): ScreenRequirement? = null

  val id: ChallengeId
  val stateSerializer: KSerializer<S>
  val inputSerializer: KSerializer<I>

  fun newGame(setup: ChallengeSetup): ChallengeGame<S, I>
}

data class ChallengeSetup(
  // Join order; the Seeker comes first.
  val players: List<PlayerId>,
  val seeker: PlayerId,
  val difficulty: Difficulty,
  val seed: Long,
  val startAt: Long,
  val capabilities: Map<PlayerId, Set<String>> = emptyMap(),
  // Present only for games with a screen requirement, measured during the briefing.
  val screens: Map<PlayerId, ScreenProfile> = emptyMap(),
)

// Called only from the Seeker's session, one call at a time.
interface ChallengeGame<S : Any, I : Any> {
  val state: S
  val status: GameStatus

  fun stateFor(player: PlayerId): S = state

  fun input(from: PlayerId, input: I, now: Long)

  fun tick(now: Long)

  // Trusted in-process observations; never decoded from player inputs.
  fun observe(observation: GameObservation, now: Long) {}
}

interface GameObservation

sealed interface GameStatus {
  data object Running : GameStatus

  data class Won(val headline: String, val stats: List<Stat> = emptyList()) : GameStatus

  // [reason] follows the culprit's name: "missed the return".
  data class Lost(
    val reason: String,
    val culprit: PlayerId? = null,
    val stats: List<Stat> = emptyList(),
  ) : GameStatus
}

@Serializable data class Stat(val label: String, val value: String)
