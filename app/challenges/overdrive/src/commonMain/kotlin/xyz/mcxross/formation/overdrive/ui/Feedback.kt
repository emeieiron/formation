package xyz.mcxross.formation.overdrive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import xyz.mcxross.formation.design.effects.ParticleField

@Stable
internal class StageFx(private val scope: CoroutineScope) {
  val punch = Animatable(0f)
  val flash = Animatable(0f)
  val wobble = Animatable(0f)
  val quake = Animatable(0f)
  val alarm = Animatable(0f)
  val particles = ParticleField()

  // Written by the dial's draw pass, which owns the layout.
  var hit = Offset.Unspecified
  var core = Offset.Unspecified
  var feltAt = 0L

  fun caught(color: Color) {
    scope.launch {
      punch.snapTo(1f)
      punch.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 500f))
    }
    fade(flash, 280)
    if (hit.isSpecified) particles.burst(hit, listOf(color, Color.White), count = 22, speed = 1_100f)
  }

  fun missed() = shake(wobble, 320f)

  fun failed() = fade(alarm, 450)

  fun won(colors: List<Color>) {
    if (core.isSpecified) particles.burst(core, colors + Color.White, count = 64, speed = 1_800f, gravity = 600f)
    fade(flash, 600)
  }

  fun lost() {
    shake(quake, 700f)
    fade(alarm, 700)
  }

  private fun fade(target: Animatable<Float, AnimationVector1D>, ms: Int) {
    scope.launch {
      target.snapTo(1f)
      target.animateTo(0f, tween(ms))
    }
  }

  // A spring released at rest with a kick decays into a shake; values are in dp.
  private fun shake(target: Animatable<Float, AnimationVector1D>, velocity: Float) {
    scope.launch {
      target.snapTo(0f)
      target.animateTo(0f, spring(dampingRatio = 0.2f, stiffness = 1_600f), initialVelocity = velocity)
    }
  }
}

@Composable
internal fun rememberStageFx(): StageFx {
  val scope = rememberCoroutineScope()
  return remember(scope) { StageFx(scope) }
}

// Runs only when [value] changes after the first composition, so a restored stage stays quiet.
@Composable
internal fun <T> OnChange(value: T, action: suspend (old: T, new: T) -> Unit) {
  val last = remember { Ref(value) }
  LaunchedEffect(value) {
    val old = last.value
    if (old != value) {
      last.value = value
      action(old, value)
    }
  }
}

private class Ref<T>(var value: T)
