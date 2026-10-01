package xyz.mcxross.formation.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import xyz.mcxross.formation.model.PlayerId

internal class HostedGame<S : Any, I : Any>(
  private val rules: ChallengeRules<S, I>,
  private val game: ChallengeGame<S, I>,
  private val json: Json,
) {
  val status: GameStatus
    get() = game.status

  fun input(from: PlayerId, input: JsonElement, now: Long) {
    val decoded =
      runCatching { json.decodeFromJsonElement(rules.inputSerializer, input) }.getOrNull()
        ?: return
    game.input(from, decoded, now)
  }

  fun tick(now: Long) = game.tick(now)

  fun state(): JsonElement = json.encodeToJsonElement(rules.stateSerializer, game.state)

  companion object {
    fun <S : Any, I : Any> start(rules: ChallengeRules<S, I>, setup: ChallengeSetup, json: Json) =
      HostedGame(rules, rules.newGame(setup), json)
  }
}
