package xyz.mcxross.formation.challenge

import androidx.compose.runtime.Composable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.builtins.serializer
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.Difficulty
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.model.OpportunityId
import xyz.mcxross.formation.model.Skr
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
      Fixture("first", 6, players = 3..2), Fixture("first", 6, players = 6..18, sizes = emptySet()),
      Fixture("first", 6, players = 6..18, sizes = setOf(6, 24)))) {
      assertFailsWith<IllegalArgumentException> { ChallengeRegistry(listOf(invalid)) }
    }
  }

  @Test
  fun onlyDeclaredGroupSizesMatchRewards() {
    val registry = ChallengeRegistry(listOf(Fixture("grid", 6, players = 6..18, sizes = setOf(6, 9, 18)), Fixture("duo", 7)))
    fun reward(game: String, players: Int) = Opportunity(OpportunityId("0f8fad5b-d9cb-469f-a165-70867728950e"),
      ChallengeId(game), Skr.of(120), players, 5_000, Difficulty.NORMAL, Long.MAX_VALUE, "Test")
    assertEquals(listOf(6, 9, 18), (2..32).filter { registry.supports(reward("grid", it)) })
    assertEquals(listOf(2), (2..32).filter { registry.supports(reward("duo", it)) })
  }

  private class Fixture(id: String, code: Int, version: Int = 1, players: IntRange = 2..2, sizes: Set<Int> = players.toSet()) :
    Challenge<Unit, Unit>() {
    override val info = ChallengeInfo(ChallengeId(id), code, "Fixture", "", "", emptyList(), Icons.Spark, 0, emptyList(), players, sizes)
    override val formatVersion = version
    override val stateSerializer = Unit.serializer()
    override val inputSerializer = Unit.serializer()
    override fun newGame(setup: ChallengeSetup) = error("Registry fixture has no game")
    override fun goal(players: Int, difficulty: Difficulty) = ""
    @Composable override fun Stage(scope: StageScope<Unit, Unit>) {}
  }
}
