package xyz.mcxross.formation.state

// What the app offers this phone. Build properties only choose the layout; hosting still needs the
// hardware proof and a linked wallet.
enum class PhoneRole {
  // Joins Formations that a Seeker starts.
  PLAYER,

  // Seeker hardware: hosts once its wallet is linked and its secure hardware proves it.
  SEEKER,

  // A developer build pretending to be a Seeker.
  TEST_SEEKER,
}

// Developer builds can show a Seeker's setup and onboarding on any phone. Read once at launch.
const val KEY_DEV_SEEKER_HARDWARE = "dev.seekerHardware"

fun phoneRole(seekerHardware: Boolean, identity: SeekerIdentity?): PhoneRole = when {
  identity?.simulated == true -> PhoneRole.TEST_SEEKER
  seekerHardware -> PhoneRole.SEEKER
  else -> PhoneRole.PLAYER
}
