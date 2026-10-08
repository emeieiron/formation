package xyz.mcxross.formation.design.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.imageResource
import org.jetbrains.compose.resources.painterResource

/** Home owns visibility; challenge modules own their presentation assets. */
val LocalArtworkActive = compositionLocalOf { false }

@Composable
fun motionEnabled(): Boolean =
  (rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) > 0f

/** A 6 × 6 sheet of 320 × 188 Blender frames, with a two-pixel transparent gutter. */
@Composable
fun MotionCover(still: DrawableResource, sheet: DrawableResource) {
  if (!LocalArtworkActive.current || !motionEnabled()) {
    Image(painterResource(still), null, Modifier.fillMaxSize())
    return
  }
  val image = imageResource(sheet)
  if (image.width == 0 || image.height == 0) {
    Image(painterResource(still), null, Modifier.fillMaxSize())
    return
  }
  val frame = remember { Animatable(0f) }
  // Quantize in derived state so the canvas redraws at the sheet's 12 fps.
  val index by remember { derivedStateOf { frame.value.toInt().coerceIn(0, 35) } }
  LaunchedEffect(sheet) {
    while (isActive) {
      frame.snapTo(0f)
      frame.animateTo(36f, tween(3_000, easing = LinearEasing))
      delay(2_000)
    }
  }
  Canvas(Modifier.fillMaxSize()) {
    val scale = minOf(size.width / 320f, size.height / 188f)
    val width = (320 * scale).roundToInt()
    val height = (188 * scale).roundToInt()
    drawImage(
      image,
      srcOffset = IntOffset(index % 6 * 324 + 2, index / 6 * 192 + 2),
      srcSize = IntSize(320, 188),
      dstOffset =
        IntOffset(
          ((size.width - width) / 2).roundToInt(),
          ((size.height - height) / 2).roundToInt(),
        ),
      dstSize = IntSize(width, height),
    )
  }
}
