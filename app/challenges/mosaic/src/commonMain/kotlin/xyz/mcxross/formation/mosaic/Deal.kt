package xyz.mcxross.formation.mosaic

import kotlin.random.Random
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ScreenProfile

internal object Deal {
  // Returns the player at each position. Positions that must centre their fragment go to the phones
  // with
  // the least spare screen on that axis, so the large phones' margins land on the outside of the
  // mosaic.
  fun deal(
    players: List<PlayerId>,
    screens: Map<PlayerId, ScreenProfile>,
    grid: Grid,
    fragment: Size,
    random: Random,
  ): List<PlayerId> {
    require(players.size == grid.size && players.distinct().size == players.size) {
      "Mosaic deals one position per phone"
    }
    val spare = players.associateWith { player ->
      val usable = Layouts.usable(screens.getValue(player), grid.placement)
      Size(usable.width - fragment.width, usable.height - fragment.height)
    }
    fun centred(position: Int) =
      (if (grid.centredAcross(position)) 1 else 0) + (if (grid.centredDown(position)) 1 else 0)
    fun cost(player: PlayerId, position: Int) =
      (if (grid.centredAcross(position)) spare.getValue(player).width else 0.0) +
        (if (grid.centredDown(position)) spare.getValue(player).height else 0.0)
    val order = (0 until grid.size).shuffled(random).sortedByDescending(::centred)
    val remaining = players.shuffled(random).toMutableList()
    val dealt = arrayOfNulls<PlayerId>(grid.size)
    for (position in order) {
      val pick = remaining.minBy { cost(it, position) }
      dealt[position] = pick
      remaining.remove(pick)
    }
    return dealt.map { requireNotNull(it) }
  }
}
