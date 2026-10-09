package xyz.mcxross.formation.longshot

import kotlinx.serialization.Serializable
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.GameObservation

@Serializable
enum class LongshotPhase {
  Choosing,
  Predicting,
  AwaitingRound,
  Funding,
  Verifying,
  Watching,
  Result,
  Skipped,
}

@Serializable
enum class Prediction {
  WIN,
  LOSE,
}

@Serializable
data class LongshotState(
  val turn: Int,
  val picker: PlayerId,
  val phase: LongshotPhase,
  val deadline: Long,
  val number: Int? = null,
  val predictions: Map<PlayerId, Prediction> = emptyMap(),
  val predicted: Set<PlayerId> = emptySet(),
  val oreRound: Long? = null,
  val winningNumber: Int? = null,
  val miningWallet: String? = null,
  val miningSignature: String? = null,
  val miningValidUntil: Long = 0,
  val connected: Boolean = true,
  val message: String? = null,
) {
  val outcome: Prediction?
    get() = winningNumber?.let { if (it == number) Prediction.WIN else Prediction.LOSE }
}

@Serializable
sealed interface LongshotInput {
  val turn: Int

  @Serializable data class Pick(override val turn: Int, val number: Int) : LongshotInput

  @Serializable
  data class Predict(override val turn: Int, val prediction: Prediction) : LongshotInput

  @Serializable
  data class Submitted(
    override val turn: Int,
    val wallet: String,
    val signature: String,
    val validUntil: Long,
  ) : LongshotInput

  @Serializable data class Next(override val turn: Int) : LongshotInput

  @Serializable data class Skip(override val turn: Int) : LongshotInput
}

// Only the host's ORE receipt and result observer supplies these events.
sealed interface LongshotObservation : GameObservation {
  val turn: Int

  data class Bound(override val turn: Int, val round: Long) : LongshotObservation

  data class Funded(override val turn: Int, val round: Long, val signature: String) :
    LongshotObservation

  data class Rejected(override val turn: Int, val signature: String, val reason: String) :
    LongshotObservation

  data class Resolved(override val turn: Int, val round: Long, val number: Int) :
    LongshotObservation

  data class Unresolved(override val turn: Int, val round: Long) : LongshotObservation

  data class Connection(override val turn: Int, val available: Boolean) : LongshotObservation
}

object LongshotTerms {
  const val LAMPORTS = 1_000_000L
  const val AMOUNT = "0.001 test SOL"

  fun reference(session: String, game: Int, turn: Int) = "$session/$game/$turn"
}
