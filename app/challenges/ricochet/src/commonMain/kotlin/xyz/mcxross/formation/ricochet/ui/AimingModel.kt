package xyz.mcxross.formation.ricochet.ui

import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.ricochet.Arena
import xyz.mcxross.formation.ricochet.Collision
import xyz.mcxross.formation.ricochet.ImpactKind
import xyz.mcxross.formation.ricochet.Paddle
import xyz.mcxross.formation.ricochet.Physics
import xyz.mcxross.formation.ricochet.Pulse
import xyz.mcxross.formation.ricochet.Target
import xyz.mcxross.formation.ricochet.targetCollision

internal class AimingModel(val y: Double) {
  val target = Target(0, 0.8, 0.12)
  private val contact =
    Pulse(Arena.PADDLE_X + Arena.PADDLE_WIDTH / 2 + Arena.RADIUS, CONTACT_Y, -1.0, 0.0)
  val outgoing =
    Physics.reflect(
      contact,
      Collision(0.0, 1.0, 0.0, ImpactKind.Paddle, 0),
      listOf(Paddle(PlayerId("preview"), 0, y)),
      PADDLE_HEIGHT,
    )
  private val seconds =
    minOf(
      (0.92 - outgoing.x) / outgoing.vx,
      when {
        outgoing.vy < 0 -> (0.02 - outgoing.y) / outgoing.vy
        outgoing.vy > 0 -> (0.58 - outgoing.y) / outgoing.vy
        else -> Double.POSITIVE_INFINITY
      },
    )
  private val targetAt = targetCollision(outgoing, target)?.time?.takeIf { it in 0.0..seconds }
  private val travelSeconds = targetAt ?: seconds
  val end = Physics.travel(outgoing, travelSeconds)
  val hitsTarget = targetAt != null

  fun sample(phase: Float): Pulse =
    if (phase < 0.45f) {
      contact.copy(x = 0.9 - (0.9 - contact.x) * phase / 0.45)
    } else Physics.travel(outgoing, travelSeconds * (phase - 0.45) / 0.55)

  companion object {
    const val CONTACT_Y = 0.34
    const val PADDLE_HEIGHT = 0.22
    val range = (CONTACT_Y - PADDLE_HEIGHT / 2)..(CONTACT_Y + PADDLE_HEIGHT / 2)
  }
}
