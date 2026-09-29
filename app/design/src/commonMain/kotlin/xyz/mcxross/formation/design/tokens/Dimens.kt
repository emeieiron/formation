package xyz.mcxross.formation.design.tokens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

object Space {
  val xxs = 2.dp
  val xs = 4.dp
  val s = 8.dp
  val m = 12.dp
  val l = 16.dp
  val xl = 20.dp
  val xxl = 24.dp
  val x3l = 32.dp
  val x4l = 40.dp
  val x5l = 48.dp
  val x6l = 64.dp

  val gutter = 20.dp
}

object Radius {
  val xs = 6.dp
  val s = 10.dp
  val m = 16.dp
  val l = 22.dp
  val xl = 28.dp
}

object Shapes {
  val control = RoundedCornerShape(Radius.m)
  val field = RoundedCornerShape(Radius.m)
  val card = RoundedCornerShape(Radius.l)
  val panel = RoundedCornerShape(Radius.xl)
  val tag = RoundedCornerShape(Radius.xs)
  val tile = RoundedCornerShape(Radius.s)
  val sheet = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl)
  val pill = RoundedCornerShape(50)
  val circle = CircleShape
}

object Sizes {
  val controlLarge = 56.dp
  val controlMedium = 48.dp
  val controlSmall = 36.dp

  val iconXs = 14.dp
  val iconS = 16.dp
  val iconM = 20.dp
  val iconL = 24.dp
  val iconXl = 32.dp

  val lightS = 28.dp
  val lightM = 40.dp
  val lightL = 56.dp
  val lightXl = 88.dp

  val topBar = 56.dp
  val hairline = 1.dp
}

object Motion {
  val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
  val emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
  val exit = CubicBezierEasing(0.3f, 0f, 1f, 1f)

  const val FAST = 140
  const val BASE = 260
  const val SLOW = 420

  fun <T> standard(duration: Int = BASE, delay: Int = 0): FiniteAnimationSpec<T> =
    tween(duration, delay, standard)

  fun <T> emphasized(duration: Int = SLOW, delay: Int = 0): FiniteAnimationSpec<T> =
    tween(duration, delay, emphasized)

  fun <T> exit(duration: Int = FAST): FiniteAnimationSpec<T> = tween(duration, easing = exit)

  fun <T> bouncy(): FiniteAnimationSpec<T> =
    spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)

  fun <T> snappy(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 900f)
}
