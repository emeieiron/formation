package xyz.mcxross.formation.ricochet

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal data class Contact(
  val kind: ImpactKind,
  val x: Double,
  val y: Double,
  val side: Int? = null,
  val target: Int? = null,
  val grazed: Boolean = false,
)

internal data class Flight(
  val pulse: Pulse,
  val targets: List<Target>,
  val contacts: List<Contact>,
  val missed: Boolean,
  val momentum: Momentum,
  val charge: Charge,
)

internal object Physics {
  fun step(
    pulse: Pulse,
    targets: List<Target>,
    paddles: List<Paddle>,
    height: Double,
    seconds: Double,
    momentum: Momentum = Momentum(),
    charge: Charge = Charge(),
  ): Flight {
    var ball = pulse
    var remaining = seconds
    var tiles = targets
    var pace = momentum
    var power = charge
    val contacts = mutableListOf<Contact>()
    repeat(24) {
      val hit = next(ball, tiles, paddles, height)
      if (hit == null || hit.time > remaining) {
        ball = travel(ball, remaining)
        return Flight(ball, tiles, contacts, false, pace, power)
      }
      ball = travel(ball, hit.time)
      remaining -= hit.time
      val grazed =
        hit.kind == ImpactKind.Paddle &&
          abs(ball.y - paddles.first { it.side == hit.side }.y) >= height * 0.425
      val pierced = hit.kind == ImpactKind.Target && power.armed
      contacts +=
        Contact(
          if (pierced) ImpactKind.Pierce else hit.kind,
          ball.x,
          ball.y,
          hit.side,
          hit.target,
          grazed,
        )
      if (hit.kind == ImpactKind.Miss)
        return Flight(ball, tiles, contacts, true, Momentum(), Charge())
      if (hit.kind == ImpactKind.Target) tiles = tiles.filter { it.id != hit.target }
      if (pierced) power = Charge() else ball = reflect(ball, hit, paddles, height)
      if (hit.kind == ImpactKind.Paddle) {
        val nextPace = pace.returned(hit.side!!)
        val boost = nextPace.factor / pace.factor
        ball = ball.copy(vx = ball.vx * boost, vy = ball.vy * boost)
        val nextPower = power.returned(nextPace.exchanges > pace.exchanges)
        if (!power.armed && nextPower.armed)
          contacts += Contact(ImpactKind.Charge, ball.x, ball.y, hit.side)
        power = nextPower
        pace = nextPace
      }
      if (remaining <= 0) return Flight(ball, tiles, contacts, false, pace, power)
    }
    error("Ricochet exceeded the collision budget")
  }

  fun next(
    pulse: Pulse,
    targets: List<Target>,
    paddles: List<Paddle>,
    height: Double,
    catchAll: Boolean = false,
  ): Collision? {
    val hits = mutableListOf<Collision>()
    if (pulse.vy < 0)
      hits += Collision((Arena.RADIUS - pulse.y) / pulse.vy, 0.0, 1.0, ImpactKind.Wall)
    if (pulse.vy > 0)
      hits +=
        Collision((Arena.HEIGHT - Arena.RADIUS - pulse.y) / pulse.vy, 0.0, -1.0, ImpactKind.Wall)
    val side = if (pulse.vx < 0) 0 else 1
    val direction = if (side == 0) 1.0 else -1.0
    val face = Arena.paddleX(side) + direction * (Arena.PADDLE_WIDTH / 2 + Arena.RADIUS)
    val time = (face - pulse.x) / pulse.vx
    val paddle = paddles.first { it.side == side }
    if (
      time >= 0 &&
        (catchAll || abs(pulse.y + pulse.vy * time - paddle.y) <= height / 2 + Arena.RADIUS)
    ) {
      hits += Collision(time, direction, 0.0, ImpactKind.Paddle, side)
    }
    val edge = if (side == 0) -Arena.RADIUS else Arena.WIDTH + Arena.RADIUS
    hits += Collision((edge - pulse.x) / pulse.vx, direction, 0.0, ImpactKind.Miss, side)
    targets.mapNotNullTo(hits) { targetCollision(pulse, it) }
    return hits.filter { it.time >= 0 && it.time.isFinite() }.minByOrNull { it.time }
  }

  fun travel(pulse: Pulse, seconds: Double) =
    pulse.copy(x = pulse.x + pulse.vx * seconds, y = pulse.y + pulse.vy * seconds)

  fun reflect(pulse: Pulse, hit: Collision, paddles: List<Paddle>, height: Double): Pulse {
    val shifted = pulse.copy(x = pulse.x + hit.nx * EPSILON, y = pulse.y + hit.ny * EPSILON)
    if (hit.kind != ImpactKind.Paddle) {
      return shifted.copy(
        vx = if (hit.nx != 0.0) -pulse.vx else pulse.vx,
        vy = if (hit.ny != 0.0) -pulse.vy else pulse.vy,
      )
    }
    val paddle = paddles.first { it.side == hit.side }
    val angle = ((pulse.y - paddle.y) / (height / 2)).coerceIn(-1.0, 1.0) * Arena.MAX_ANGLE
    val speed = hypot(pulse.vx, pulse.vy)
    return shifted.copy(vx = cos(angle) * speed * hit.nx, vy = sin(angle) * speed)
  }

  private const val EPSILON = 1e-7
}
