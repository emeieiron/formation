package xyz.mcxross.formation.ricochet

import androidx.compose.runtime.Composable
import xyz.mcxross.formation.challenge.Challenge
import xyz.mcxross.formation.challenge.ChallengeInfo
import xyz.mcxross.formation.challenge.Move
import xyz.mcxross.formation.challenge.Role
import xyz.mcxross.formation.challenge.Sense
import xyz.mcxross.formation.challenge.StageScope
import xyz.mcxross.formation.challenge.Step
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.ChallengeGame
import xyz.mcxross.formation.ricochet.ui.RicochetStage

object Ricochet : Challenge<RicochetState, MovePaddle>() {
  override val formatVersion = 3
  override val info = ChallengeInfo(
    id = ChallengeId("ricochet"), code = 7, title = "Ricochet",
    tagline = "One pulse. Two players.",
    summary = "Keep the pulse moving between your phones. Clean exchanges build speed.",
    steps = listOf(
      Step(Icons.Tap, "Tap or drag vertically to move your outer paddle."),
      Step(Icons.ArrowRight, "Catch the pulse. An edge hit angles the return."),
      Step(Icons.Target, "Clear six targets before time runs out. Three misses end the attempt."),
    ),
    icon = Icons.Target, light = 0, senses = listOf(Sense.Touch, Sense.Timing), players = 2..2,
  )
  override val stateSerializer = RicochetState.serializer()
  override val inputSerializer = MovePaddle.serializer()
  override fun newGame(setup: ChallengeSetup): ChallengeGame<RicochetState, MovePaddle> = RicochetGame(setup)
  override fun goal(players: Int, difficulty: Difficulty) = "6 targets · 3 misses · ${Pacing(difficulty).limitMs / 1_000} seconds"

  override fun autopilot(state: RicochetState, me: PlayerId, now: Long): Move<MovePaddle>? {
    if (state.finishedAt != null || now < state.startAt || now >= state.endsAt) return null
    val position = ReturnGuide.position(state, me) ?: return null
    val sequence = state.paddle(me).sequence + 1
    return Move("${state.rally}:${state.at}:$sequence", MovePaddle(state.rally, sequence, position))
  }

  override fun role(players: List<PlayerId>, seeker: PlayerId, me: PlayerId): Role {
    val left = players.first() == me
    return Role(if (left) "Left paddle" else "Right paddle", "Your phone shows the ${if (left) "left" else "right"} half of the arena.",
      if (left) Icons.ArrowLeft else Icons.ArrowRight)
  }

  @Composable override fun Stage(scope: StageScope<RicochetState, MovePaddle>) = RicochetStage(scope)
}
