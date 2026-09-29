package xyz.mcxross.formation.challenge.sync

import androidx.compose.runtime.Composable
import xyz.mcxross.formation.challenge.Challenge
import xyz.mcxross.formation.challenge.ChallengeInfo
import xyz.mcxross.formation.challenge.Move
import xyz.mcxross.formation.challenge.Sense
import xyz.mcxross.formation.challenge.StageScope
import xyz.mcxross.formation.challenge.Step
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeGame
import xyz.mcxross.formation.session.ChallengeSetup

object Sync : Challenge<SyncState, SyncInput>() {
  override val info =
    ChallengeInfo(
      id = ChallengeId("sync"),
      code = 3,
      title = "Sync",
      tagline = "Different moves, one moment",
      summary =
        "Each phone gets its own move: a tap, a swipe, a shake, a flip. The group must land them all on the same beat. In the later rounds the countdown vanishes and you keep time together.",
      steps =
        listOf(
          Step(Icons.Target, "Your move is on your screen. Everyone else has a different one."),
          Step(Icons.Clock, "Rings close in and every phone ticks together. Move on the beat."),
          Step(Icons.Users, "Later the countdown disappears. Count the last beats out loud."),
        ),
      icon = Icons.Sync,
      light = 6,
      senses = listOf(Sense.Timing, Sense.Motion, Sense.Voice),
    )

  override val stateSerializer = SyncState.serializer()
  override val inputSerializer = SyncInput.serializer()

  override fun newGame(setup: ChallengeSetup): ChallengeGame<SyncState, SyncInput> = SyncGame(setup)

  override fun goal(players: Int, difficulty: Difficulty) =
    "${SyncGame.rounds(difficulty)} rounds, every move on the same beat"

  @Composable override fun Stage(scope: StageScope<SyncState, SyncInput>) = SyncStage(scope)

  override fun autopilot(state: SyncState, me: PlayerId, now: Long): Move<SyncInput>? {
    val round = state.round ?: return null
    if (me.value !in round.tasks || me.value in state.acted || now < round.moment) return null
    return Move("round-${round.attempt}", SyncInput(round.attempt, now))
  }
}
