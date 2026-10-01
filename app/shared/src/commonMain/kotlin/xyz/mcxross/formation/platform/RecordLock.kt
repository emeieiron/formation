package xyz.mcxross.formation.platform

// Serialize each record's read, durable write, and publication across session/RPC coroutines.
internal expect class RecordLock() {
  fun <T> locked(block: () -> T): T
}
