package xyz.mcxross.formation.mosaic

data class Vec(val x: Double, val y: Double) {
  operator fun plus(other: Vec) = Vec(x + other.x, y + other.y)

  operator fun minus(other: Vec) = Vec(x - other.x, y - other.y)

  operator fun times(scale: Double) = Vec(x * scale, y * scale)
}

internal sealed interface Segment {
  val to: Vec

  data class Line(override val to: Vec) : Segment

  data class Cubic(val first: Vec, val second: Vec, override val to: Vec) : Segment
}

// One closed contour of SVG path data. Supports the commands the logomark uses: M, L, H, V, C and
// Z.
internal class PathData(val start: Vec, val segments: List<Segment>) {
  fun flatten(steps: Int = 12): List<Vec> {
    val points = mutableListOf(start)
    var from = start
    for (segment in segments) {
      when (segment) {
        is Segment.Line -> points += segment.to
        is Segment.Cubic ->
          for (step in 1..steps) {
            val t = step.toDouble() / steps
            val u = 1 - t
            points +=
              from * (u * u * u) +
                segment.first * (3 * u * u * t) +
                segment.second * (3 * u * t * t) +
                segment.to * (t * t * t)
          }
      }
      from = segment.to
    }
    return points
  }

  companion object {
    private val token = Regex("[MLHVCZmlhvcz]|-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")

    fun parse(data: String): PathData {
      val tokens = token.findAll(data).map { it.value }.toList()
      var index = 0
      var command = ' '
      var at = Vec(0.0, 0.0)
      var start: Vec? = null
      val segments = mutableListOf<Segment>()
      fun number() = tokens[index++].toDouble()
      fun point(relative: Boolean) = Vec(number(), number()).let { if (relative) it + at else it }
      while (index < tokens.size) {
        if (tokens[index].first().isLetter()) command = tokens[index++].first()
        val relative = command.isLowerCase()
        when (command.uppercaseChar()) {
          'M' -> {
            require(start == null) { "Only one contour per path" }
            at = point(relative)
            start = at
            // Coordinates after a move continue as lines.
            command = if (relative) 'l' else 'L'
          }
          'L' -> {
            at = point(relative)
            segments += Segment.Line(at)
          }
          'H' -> {
            at = Vec(number() + if (relative) at.x else 0.0, at.y)
            segments += Segment.Line(at)
          }
          'V' -> {
            at = Vec(at.x, number() + if (relative) at.y else 0.0)
            segments += Segment.Line(at)
          }
          'C' -> {
            val first = point(relative)
            val second = point(relative)
            val to = point(relative)
            segments += Segment.Cubic(first, second, to)
            at = to
          }
          'Z' -> {
            at = start ?: at
            command = ' '
          }
          else -> error("Unsupported path command $command")
        }
      }
      return PathData(requireNotNull(start) { "Empty path" }, segments)
    }
  }
}
