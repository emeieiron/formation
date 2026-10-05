package xyz.mcxross.formation.state

// What the app offers this phone. A linked wallet makes any phone a host; Seeker hardware only decides that
// an unlinked phone is led to linking rather than to joining.
enum class PhoneRole {
  // Joins Formations that Seeker owners start.
  PLAYER,

  // Seeker hardware whose wallet isn't linked yet.
  SEEKER,

  // A linked wallet holding a Genesis Token, on any phone.
  HOST,

  // A developer build pretending to be a Seeker.
  TEST_HOST,
}

// Developer builds can show a Seeker's onboarding on any phone. Read once at launch.
const val KEY_DEV_SEEKER_HARDWARE = "dev.seekerHardware"

fun phoneRole(seekerHardware: Boolean, identity: SeekerIdentity?): PhoneRole = when {
  identity?.simulated == true -> PhoneRole.TEST_HOST
  identity != null -> PhoneRole.HOST
  seekerHardware -> PhoneRole.SEEKER
  else -> PhoneRole.PLAYER
}
