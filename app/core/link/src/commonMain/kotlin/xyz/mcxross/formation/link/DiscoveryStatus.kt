package xyz.mcxross.formation.link

sealed interface DiscoveryStatus {
  data object Searching : DiscoveryStatus
  data class Failed(val reason: Reason) : DiscoveryStatus

  enum class Reason(val message: String) {
    START("Nearby discovery could not start. Retry or scan the Seeker's QR code."),
    STOP("Nearby discovery stopped unexpectedly. Retry or scan the Seeker's QR code."),
    RESOLVE("A nearby Seeker could not be resolved. Retry or scan its QR code."),
    TIMEOUT("A nearby Seeker did not respond in time. Retry or scan its QR code."),
  }
}
