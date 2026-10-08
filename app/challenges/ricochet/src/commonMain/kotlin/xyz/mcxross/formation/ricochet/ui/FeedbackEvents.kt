package xyz.mcxross.formation.ricochet.ui

import xyz.mcxross.formation.challenge.GameCue
import xyz.mcxross.formation.ricochet.ImpactKind
import xyz.mcxross.formation.ricochet.RicochetState

internal class FeedbackEvents(initial: RicochetState) {
  private var seen = initial.impacts.lastOrNull()?.id ?: 0
  private var beat: Pair<Int, Long>? = null
  private var lastCueAt: Long? = null

  fun next(state: RicochetState, side: Int, now: Long): GameCue? {
    val events = state.impacts.filter { it.id > seen && now - it.at in -50L..350L }
    seen = maxOf(seen, state.impacts.lastOrNull()?.id ?: 0)
    val currentBeat = state.rally to (now - state.serveAt).coerceAtLeast(0) / BEAT_MS
    val newBeat =
      beat?.let { it.first == currentBeat.first && currentBeat.second > it.second } == true
    if (beat?.first != currentBeat.first || newBeat) beat = currentBeat
    if (
      state.finishedAt != null ||
        now < state.startAt ||
        now >= state.endsAt ||
        now - state.at !in -50L..300L
    )
      return null
    val ownReturn = events.lastOrNull { it.kind == ImpactKind.Paddle && it.side == side }
    val cue =
      when {
        events.any { it.kind == ImpactKind.Miss } -> GameCue.Miss
        now < state.serveAt -> null
        events.any { it.kind == ImpactKind.Pierce } -> GameCue.Pierce
        events.any { it.kind == ImpactKind.Charge } -> GameCue.Charge
        events.any { it.kind == ImpactKind.Target } -> GameCue.Target
        ownReturn?.grazed == true -> GameCue.CloseCall
        ownReturn != null ->
          if (state.momentum.factor >= 1.35) GameCue.FastReturn else GameCue.Return
        newBeat && state.misses == 2 && (lastCueAt?.let { now - it >= 450 } != false) ->
          GameCue.Danger
        else -> null
      }
    if (cue != null) lastCueAt = now
    return cue
  }

  companion object {
    const val BEAT_MS = 2_400L
  }
}

internal fun dangerPulse(state: RicochetState, now: Long): Float {
  if (
    state.misses != 2 ||
      state.finishedAt != null ||
      now < state.serveAt ||
      now >= state.endsAt ||
      now - state.at !in -50L..300L
  )
    return 0f
  val phase = (now - state.serveAt) % FeedbackEvents.BEAT_MS
  return when {
    phase < 120 -> 1f - phase / 120f
    phase in 160..320 -> (1f - (phase - 160) / 160f) * 0.65f
    else -> 0f
  }
}
