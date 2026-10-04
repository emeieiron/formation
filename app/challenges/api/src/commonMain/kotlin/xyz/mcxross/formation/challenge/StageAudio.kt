package xyz.mcxross.formation.challenge

enum class GameCue { Return, FastReturn, CloseCall, Target, Miss, Danger, Charge, Pierce }

fun interface StageAudio {
  fun play(cue: GameCue)

  companion object {
    val None = StageAudio {}
  }
}
