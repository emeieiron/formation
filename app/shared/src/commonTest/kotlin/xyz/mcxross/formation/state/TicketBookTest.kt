package xyz.mcxross.formation.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mcxross.formation.model.ChallengeId
import xyz.mcxross.formation.model.OpportunityId
import xyz.mcxross.formation.model.Skr
import xyz.mcxross.formation.platform.KeyValueStore

class TicketBookTest {
  private val store =
    object : KeyValueStore {
      val map = mutableMapOf<String, String>()

      override fun get(key: String) = map[key]

      override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
      }
    }

  private fun ticket(unlocked: Boolean, claimedTo: String? = null) =
    ClaimTicket(
      OpportunityId("o-1"),
      "11111111111111111111111111111111",
      ChallengeId("sync"),
      "Theo",
      Skr.of(60),
      0,
      "root",
      emptyList(),
      1,
      "",
      claimedTo = claimedTo,
      unlocked = unlocked,
    )

  @Test
  fun aShareSavedAtTheSealIsReplacedByTheUnlock() {
    val book = TicketBook(store, "t")
    book.keep(ticket(unlocked = false))
    book.keep(ticket(unlocked = true, claimedTo = "Wallet111"))
    assertEquals(listOf("Wallet111"), book.tickets.value.map { it.claimedTo })
    assertTrue(book.tickets.value.single().unlocked)

    book.keep(ticket(unlocked = false))
    assertTrue(book.tickets.value.single().claimed, "a later waiting copy never undoes the unlock")
    assertEquals(1, TicketBook(store, "t").tickets.value.size, "tickets survive a restart")
  }
}
