package xyz.mcxross.formation.mosaic

import xyz.mcxross.formation.model.PlayerId

// Debug assistance from public state. Both phones of a seam pinch it in the same slot of a shared
// schedule, sending from the middle of the slot so phones a little apart in time still agree.
internal object Assist {
  const val DELAY_MS = 1_000L
  const val SLOT_MS = 700L
  private const val SEND_FROM_MS = 200L
  private const val SEND_UNTIL_MS = 450L
  private const val DRAG_MS = 180L

  fun pinch(state: MosaicState, me: PlayerId, now: Long): Pair<String, Pinch>? {
    if (state.finishedAt != null) return null
    val elapsed = now - state.startAt - DELAY_MS
    if (elapsed < 0 || elapsed % SLOT_MS !in SEND_FROM_MS until SEND_UNTIL_MS) return null
    val slot = elapsed / SLOT_MS
    val seams = state.seams
    val seam = seams[(slot % seams.size).toInt()]
    if (seam.id in state.sealed) return null
    val position = state.position(me).takeIf { it >= 0 } ?: return null
    val edge = seam.edgeOf(position) ?: return null
    val along = if (edge.vertical) state.fragment.height / 2 else state.fragment.width / 2
    return "assist:$slot" to Pinch(now, edge, along, now - DRAG_MS, now)
  }
}
