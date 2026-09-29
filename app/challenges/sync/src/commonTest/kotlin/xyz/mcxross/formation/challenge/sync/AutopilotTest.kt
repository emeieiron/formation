package xyz.mcxross.formation.challenge.sync

import kotlin.test.Test
import kotlin.test.assertIs
import xyz.mcxross.formation.challenge.playOut
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.session.ChallengeSetup
import xyz.mcxross.formation.session.GameStatus

class AutopilotTest {
  @Test
  fun aCoordinatedGroupWins() {
    for (size in listOf(2, 3, 5, 8)) {
      for (difficulty in Difficulty.entries) {
        val players = List(size) { PlayerId("p${it + 1}") }
        val status =
          Sync.playOut(
            ChallengeSetup(players, players[0], difficulty, seed = 17L * size, startAt = 1_000)
          )
        assertIs<GameStatus.Won>(status, "$size players on $difficulty: $status")
      }
    }
  }
}
