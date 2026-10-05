package xyz.mcxross.formation.state

import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneRoleTest {
  private val linked = SeekerIdentity("Wallet111", "Sgt111", simulated = false)
  private val pretend = SeekerIdentity("Claim111", null, simulated = true)

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
  fun aPretendSeekerIsATestHostAnywhere() {
    assertEquals(PhoneRole.TEST_HOST, phoneRole(seekerHardware = false, identity = pretend))
    assertEquals(PhoneRole.TEST_HOST, phoneRole(seekerHardware = true, identity = pretend))
  }
}
