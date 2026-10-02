package xyz.mcxross.formation.challenge

import androidx.compose.runtime.Composable
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.serialization.builtins.serializer
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.session.ChallengeSetup

class ChallengeRegistryTest {
  @Test
  fun registrationRejectsAmbiguousAndRetiredRewardMappings() {
    assertFailsWith<IllegalArgumentException> {
      ChallengeRegistry(listOf(Fixture("first", 6), Fixture("first", 7)))
    }
    assertFailsWith<IllegalArgumentException> {
      ChallengeRegistry(listOf(Fixture("first", 6), Fixture("second", 6)))
    }
    assertFailsWith<IllegalArgumentException> {
      ChallengeRegistry(listOf(Fixture("retired", 6)), retiredIds = setOf(ChallengeId("retired")))
    }
    assertFailsWith<IllegalArgumentException> {
      ChallengeRegistry(listOf(Fixture("first", 6)), retiredCodes = setOf(6))
    }
  }

  @Test
  fun registrationRejectsMetadataTheSessionAndVaultCannotSupport() {
    for (invalid in listOf(Fixture("", 6), Fixture("first", 0), Fixture("first", 65536),
      Fixture("first", 6, version = 0), Fixture("first", 6, players = 2..33),
      Fixture("first", 6, players = 3..2))) {
      assertFailsWith<IllegalArgumentException> { ChallengeRegistry(listOf(invalid)) }
    }
  }

  private class Fixture(id: String, code: Int, version: Int = 1, players: IntRange = 2..2) : Challenge<Unit, Unit>() {
    override val info = ChallengeInfo(ChallengeId(id), code, "Fixture", "", "", emptyList(), Icons.Spark, 0, emptyList(), players)
    override val formatVersion = version
    override val stateSerializer = Unit.serializer()
    override val inputSerializer = Unit.serializer()
    override fun newGame(setup: ChallengeSetup) = error("Registry fixture has no game")
    override fun goal(players: Int, difficulty: Difficulty) = ""
    @Composable override fun Stage(scope: StageScope<Unit, Unit>) {}
  }
}
