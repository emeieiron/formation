package xyz.mcxross.formation.caravan

// Sensor readings and taps share one sequence per round. A delayed host frame must not give two
// different local strides the same index; gaps are valid when the pack rule rejects an attempt.
internal class StrideSequence {
  private var lastIndex = 0

  fun next(walker: WalkerState, at: Long): Stride {
    lastIndex = maxOf(lastIndex, walker.lastStrideIndex, walker.steps) + 1
    return Stride(lastIndex, at)
  }
}
