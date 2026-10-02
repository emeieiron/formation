package xyz.mcxross.formation.challenge

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import xyz.mcxross.formation.model.ChallengeId

@Immutable
data class ChallengeInfo(
  val id: ChallengeId,
  // Stored on chain by the vault program; never renumber.
  val code: Int,
  val title: String,
  val tagline: String,
  val summary: String,
  val steps: List<Step>,
  val icon: ImageVector,
  val light: Int,
  val senses: List<Sense>,
  val players: IntRange = 2..32,
)

@Immutable data class Step(val icon: ImageVector, val text: String)

@Immutable data class Role(val title: String, val text: String, val icon: ImageVector)

enum class Sense(val label: String) {
  Touch("Touch"),
  Timing("Timing"),
  Motion("Motion"),
  Pose("Pose"),
  Voice("Talk it out"),
}
