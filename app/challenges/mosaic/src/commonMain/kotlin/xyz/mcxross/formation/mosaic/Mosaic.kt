package xyz.mcxross.formation.mosaic

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
import xyz.mcxross.formation.mosaic.ui.MosaicStage
import xyz.mcxross.formation.mosaic.ui.PinchIntroduction
import xyz.mcxross.formation.session.ChallengeGame
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.ScreenRequirement

object Mosaic : Challenge<MosaicState, Pinch>() {
  override val formatVersion = 1
  override val info = ChallengeInfo(
    id = ChallengeId("mosaic"), code = 8, title = "Mosaic",
    tagline = "Every phone holds a piece of the mark.",
    summary = "Lay the phones in three rows to rebuild the Solana mark, then pinch across every seam before time runs out.",
    steps = listOf(
      Step(Icons.Phone, "Each phone holds one piece of the Solana mark."),
      Step(Icons.Tiles, "Lay the phones in three rows until the bars line up."),
      Step(Icons.Pinch, "Pinch across every seam to seal it before time runs out. Three wrong pairs end the attempt."),
    ),
    icon = Icons.Tiles, light = 0, senses = listOf(Sense.Touch, Sense.Voice),
    players = 6..18, groupSizes = Layouts.groupSizes,
  )
  override val stateSerializer = MosaicState.serializer()
  override val inputSerializer = Pinch.serializer()
  override val fullScreen = true
  override val introduction: @Composable () -> Unit = { PinchIntroduction() }

  override fun screenRequirement(players: Int) = ScreenRequirement(Layouts.MIN_SHORT_MM, Layouts.MIN_LONG_MM)

  override fun newGame(setup: ChallengeSetup): ChallengeGame<MosaicState, Pinch> = MosaicGame(setup)

  override fun goal(players: Int, difficulty: Difficulty): String {
    val seams = runCatching { Layouts.grid(players).seams().size }.getOrNull() ?: return "Rebuild the mark"
    return "$seams seams · ${MosaicState.MAX_MISSES} wrong pairs · ${Pacing(difficulty).limitMs(seams) / 1_000} seconds"
  }

  override fun autopilot(state: MosaicState, me: PlayerId, now: Long): Move<Pinch>? =
    Assist.pinch(state, me, now)?.let { (key, pinch) -> Move(key, pinch) }

  @Composable override fun Stage(scope: StageScope<MosaicState, Pinch>) = MosaicStage(scope)
}
