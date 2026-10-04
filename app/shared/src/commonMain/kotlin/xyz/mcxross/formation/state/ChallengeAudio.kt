package xyz.mcxross.formation.state

import xyz.mcxross.formation.challenge.GameCue
import xyz.mcxross.formation.challenge.StageAudio
import xyz.mcxross.formation.platform.SoundCue

internal class ChallengeAudio(private val sounds: SoundEffects) : StageAudio {
  override fun play(cue: GameCue) = sounds.play(when (cue) {
    GameCue.Return -> SoundCue.RETURN
    GameCue.FastReturn -> SoundCue.FAST_RETURN
    GameCue.CloseCall -> SoundCue.CLOSE_CALL
    GameCue.Target -> SoundCue.TARGET
    GameCue.Miss -> SoundCue.MISS
    GameCue.Danger -> SoundCue.DANGER
    GameCue.Charge -> SoundCue.CHARGE
    GameCue.Pierce -> SoundCue.PIERCE
  })
}
