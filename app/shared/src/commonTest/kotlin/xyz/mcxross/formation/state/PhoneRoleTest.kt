package xyz.mcxross.formation.state

import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneRoleTest {
  private val linked = SeekerIdentity("Wallet111", "Sgt111", "", "")

  @Test
  fun aLinkedWalletHostsOnAnyPhone() {
    assertEquals(PhoneRole.HOST, phoneRole(seekerHardware = false, identity = linked))
    assertEquals(PhoneRole.HOST, phoneRole(seekerHardware = true, identity = linked))
  }

  @Test
  fun unlinkedPhonesJoinOrAreLedToLinking() {
    assertEquals(PhoneRole.PLAYER, phoneRole(seekerHardware = false, identity = null))
    assertEquals(PhoneRole.SEEKER, phoneRole(seekerHardware = true, identity = null))
  }

  @Test
  fun aTestSeekerHostsLikeAnyOther() {
    assertEquals(
      PhoneRole.HOST,
      phoneRole(seekerHardware = false, identity = linked.copy(test = true)),
    )
  }
}
