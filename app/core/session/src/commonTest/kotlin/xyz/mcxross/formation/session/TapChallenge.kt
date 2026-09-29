package xyz.mcxross.formation.session

import kotlinx.serialization.Serializable
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.PlayerId

object TapChallenge : ChallengeRules<TapChallenge.State, TapChallenge.Tap> {
  @Serializable
  data class State(val taps: Map<String, Int>, val target: Int, val broken: String? = null)

  @Serializable data class Tap(val wrong: Boolean = false)

  override val id = ChallengeId("tap")
  override val stateSerializer = State.serializer()
  override val inputSerializer = Tap.serializer()

  override fun newGame(setup: ChallengeSetup) =
    object : ChallengeGame<State, Tap> {
      override var state = State(setup.players.associate { it.value to 0 }, target = 2)
        private set

      override val status: GameStatus
        get() =
          when {
            state.broken != null ->
              GameStatus.Lost("tapped the wrong way", PlayerId(state.broken!!))
            state.taps.values.all { it >= state.target } -> GameStatus.Won("Everyone tapped")
            else -> GameStatus.Running
          }

      override fun input(from: PlayerId, input: Tap, now: Long) {
        if (now < setup.startAt) return
        state =
          if (input.wrong) state.copy(broken = from.value)
          else state.copy(taps = state.taps + (from.value to state.taps.getValue(from.value) + 1))
      }

      override fun tick(now: Long) {}
    }
}
