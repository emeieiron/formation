package xyz.mcxross.formation.session

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId

interface ChallengeRules<S : Any, I : Any> {
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
)

// Called only from the Seeker's session, one call at a time.
interface ChallengeGame<S : Any, I : Any> {
  val state: S
  val status: GameStatus

  fun input(from: PlayerId, input: I, now: Long)

  fun tick(now: Long)
}

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
