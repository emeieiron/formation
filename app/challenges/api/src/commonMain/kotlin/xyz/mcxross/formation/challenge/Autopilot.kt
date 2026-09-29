package xyz.mcxross.formation.challenge

import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.GameStatus

fun <S : Any, I : Any> Challenge<S, I>.playOut(
  setup: ChallengeSetup,
  stepMs: Long = 40,
  limitMs: Long = 10 * 60_000,
): GameStatus {
  val game = newGame(setup)
  val sent = setup.players.associateWith { HashSet<String>() }
  var now = setup.startAt
  while (game.status == GameStatus.Running && now - setup.startAt < limitMs) {
    now += stepMs
    game.tick(now)
    for (player in setup.players) {
      if (game.status != GameStatus.Running) break
      val move = autopilot(game.state, player, now) ?: continue
      if (sent.getValue(player).add(move.key)) game.input(player, move.input, now)
    }
  }
  return game.status
}
