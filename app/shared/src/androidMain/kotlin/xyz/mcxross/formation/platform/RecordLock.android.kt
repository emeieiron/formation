package xyz.mcxross.formation.platform

internal actual class RecordLock actual constructor() {
  private val lock = java.util.concurrent.locks.ReentrantLock()
  actual fun <T> locked(block: () -> T): T {
    lock.lock()
    try { return block() } finally { lock.unlock() }
  }
}
