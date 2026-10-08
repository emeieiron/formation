package xyz.mcxross.formation.state

import xyz.mcxross.formation.platform.SoundCue
import xyz.mcxross.formation.session.SessionSnapshot
import xyz.mcxross.formation.session.Stage
import xyz.mcxross.formation.session.Unlock

/** Session lifetime, rather than screen lifetime: profile updates and reconnection stay quiet. */
internal class SessionSoundEvents {
  private var assembled = false
  private val briefings = mutableSetOf<Long>()
  private val completedRounds = mutableSetOf<Int>()
  private val receipts = mutableSetOf<String>()

  fun next(snapshot: SessionSnapshot): SoundCue? =
    when (val stage = snapshot.stage) {
      Stage.Lobby -> {
        if (!assembled && snapshot.full && snapshot.players.all { it.connected }) {
          assembled = true
          SoundCue.ASSEMBLED
        } else null
      }
      is Stage.Briefing -> SoundCue.BEGIN.takeIf { briefings.add(stage.until) }
      is Stage.Won -> {
        val unlocked = stage.unlock as? Unlock.Unlocked
        if (unlocked != null) {
          // A coalesced win/unlock snapshot announces only the confirmed reward.
          completedRounds.add(snapshot.round)
          SoundCue.REWARD.takeIf { receipts.add(unlocked.receipt) }
        } else SoundCue.COMPLETE.takeIf { completedRounds.add(snapshot.round) }
      }
      else -> null
    }
}
