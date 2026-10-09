package xyz.mcxross.formation.session

import kotlinx.serialization.Serializable

// Closed vocabulary and numeric measurements prevent accidental logging of identities or keys.
@Serializable
enum class DiagnosticCode {
  SESSION_LOBBY,
  SESSION_BRIEFING,
  SESSION_PLAYING,
  SESSION_WON,
  SESSION_LOST,
  SESSION_CLOSED,
  SESSION_FINISHED,
  CONNECT_START,
  CONNECT_JOINED,
  CONNECT_RETRY,
  CONNECT_REJECTED,
  CONNECT_ENDED,
  CONNECT_UNTRUSTED,
  CLOCK_RTT,
  CLOCK_STALE,
  SEAL_PROGRESS,
  SEAL_DURATION,
  STORAGE_FAILED,
  UNLOCK_START,
  SUBMISSION_PENDING,
  SUBMISSION_CONFIRMED,
  SUBMISSION_FAILED,
  SUBMISSION_EXPIRED,
  SUBMISSION_UNCERTAIN,
  DISCOVERY_SEARCHING,
  DISCOVERY_FAILED,
  CLAIM_EXPIRED,
  RECOVERY_RESTORED,
}

@Serializable data class DiagnosticEvent(val code: DiagnosticCode, val value: Long? = null)

internal val Stage.diagnosticCode: DiagnosticCode
  get() =
    when (this) {
      Stage.Lobby -> DiagnosticCode.SESSION_LOBBY
      is Stage.Briefing -> DiagnosticCode.SESSION_BRIEFING
      is Stage.Playing -> DiagnosticCode.SESSION_PLAYING
      is Stage.Won -> DiagnosticCode.SESSION_WON
      is Stage.Lost -> DiagnosticCode.SESSION_LOST
      is Stage.Closed -> DiagnosticCode.SESSION_CLOSED
      is Stage.Finished -> DiagnosticCode.SESSION_FINISHED
    }
