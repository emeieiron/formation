package xyz.mcxross.formation.design.effects

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

private class Particle(
  var x: Float,
  var y: Float,
  var vx: Float,
  var vy: Float,
  val color: Color,
  val size: Float,
  val life: Float,
  val gravity: Float,
  val drag: Float,
  val confetti: Boolean,
  var spin: Float,
  val spinSpeed: Float,
  var age: Float = 0f,
)

// Frames only run while something is alive.
@Stable
class ParticleField(private val random: Random = Random.Default) {
  private val particles = ArrayList<Particle>()
  private val alive = MutableStateFlow(0)
  internal var frame by mutableLongStateOf(0L)

  fun burst(
    at: Offset,
    colors: List<Color>,
    count: Int = 26,
    speed: Float = 900f,
    spread: Float = 360f,
    direction: Float = -90f,
    gravity: Float = 900f,
  ) {
    repeat(count) {
      val angle = (direction + (random.nextFloat() - 0.5f) * spread) * PI.toFloat() / 180f
      val v = speed * (0.35f + random.nextFloat() * 0.65f)
      particles +=
        Particle(
          at.x,
          at.y,
          cos(angle) * v,
          sin(angle) * v,
          colors[it % colors.size],
          3f + random.nextFloat() * 5f,
          0.55f + random.nextFloat() * 0.5f,
          gravity,
          2.2f,
          false,
          0f,
          0f,
        )
    }
    alive.value = particles.size
  }

  fun confetti(width: Float, colors: List<Color>, count: Int = 90) {
    repeat(count) {
      particles +=
        Particle(
          random.nextFloat() * width,
          -20f - random.nextFloat() * 300f,
          (random.nextFloat() - 0.5f) * 220f,
          180f + random.nextFloat() * 260f,
          colors[it % colors.size],
          7f + random.nextFloat() * 7f,
          2.6f + random.nextFloat() * 1.6f,
          120f,
          0.4f,
          true,
          random.nextFloat() * 360f,
          (random.nextFloat() - 0.5f) * 540f,
        )
    }
    alive.value = particles.size
  }

  internal val empty: Boolean
    get() = particles.isEmpty()

  internal suspend fun awaitWork() {
    alive.first { it > 0 }
  }

  internal fun step(dt: Float) {
    val it = particles.iterator()
    while (it.hasNext()) {
      val p = it.next()
      p.age += dt
      if (p.age >= p.life) {
        it.remove()
        continue
      }
      p.vx -= p.vx * p.drag * dt
      p.vy += p.gravity * dt - p.vy * p.drag * dt
      p.x += p.vx * dt
      p.y += p.vy * dt
      p.spin += p.spinSpeed * dt
    }
    alive.value = particles.size
  }

  internal fun draw(scope: DrawScope) =
    with(scope) {
      for (p in particles) {
        val fade = (1f - p.age / p.life).coerceIn(0f, 1f)
        if (p.confetti) {
          rotate(p.spin, Offset(p.x, p.y)) {
            drawRect(
              p.color.copy(alpha = fade),
              Offset(p.x - p.size / 2, p.y - p.size / 4),
              Size(p.size, p.size / 2),
            )
          }
        } else {
          drawCircle(p.color.copy(alpha = 0.35f * fade), p.size * 2.2f, Offset(p.x, p.y))
          drawCircle(p.color.copy(alpha = fade), p.size * (0.5f + 0.5f * fade), Offset(p.x, p.y))
        }
      }
    }
}

@Composable fun rememberParticles(): ParticleField = remember { ParticleField() }

@Composable
fun ParticleLayer(field: ParticleField, modifier: Modifier) {
  LaunchedEffect(field) {
    while (true) {
      field.awaitWork()
      var last = 0L
      while (!field.empty) {
        withFrameNanos { now ->
          val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
          last = now
          field.step(dt)
          field.frame = now
        }
      }
    }
  }
  Canvas(modifier) {
    // Reading the frame makes the canvas redraw every step.
    if (field.frame >= 0) field.draw(this)
  }
}
