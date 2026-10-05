package xyz.mcxross.formation.state

import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneRoleTest {
  private val linked = SeekerIdentity("Wallet111", "Sgt111", simulated = false)
  private val pretend = SeekerIdentity("Claim111", null, simulated = true)

  @Test
  fun otherPhonesPlayEvenWithAnOldLink() {
    assertEquals(PhoneRole.PLAYER, phoneRole(seekerHardware = false, identity = null))
    // A wallet linked before hosting needed the hardware proof doesn't make the phone a Seeker.
    assertEquals(PhoneRole.PLAYER, phoneRole(seekerHardware = false, identity = linked))
  }

  @Test
  fun seekerHardwareIsASeekerBeforeAndAfterLinking() {
    assertEquals(PhoneRole.SEEKER, phoneRole(seekerHardware = true, identity = null))
    assertEquals(PhoneRole.SEEKER, phoneRole(seekerHardware = true, identity = linked))
  }

  @Test
  fun aPretendSeekerIsATestSeekerAnywhere() {
    assertEquals(PhoneRole.TEST_SEEKER, phoneRole(seekerHardware = false, identity = pretend))
    assertEquals(PhoneRole.TEST_SEEKER, phoneRole(seekerHardware = true, identity = pretend))
  }
}
