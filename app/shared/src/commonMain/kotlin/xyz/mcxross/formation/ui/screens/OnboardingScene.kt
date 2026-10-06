package xyz.mcxross.formation.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.imageResource
import org.jetbrains.compose.resources.stringResource
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.resources.Res
import xyz.mcxross.formation.resources.onboarding_terminal
import xyz.mcxross.formation.resources.story_any_phone
import xyz.mcxross.formation.resources.story_locked
import xyz.mcxross.formation.resources.story_seeker
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// The terminal is drawn far below its rendered size; without mipmaps its edges shimmer as it moves.
internal expect fun ImageBitmap.withMipmaps(): ImageBitmap

private fun blend(a: Float, b: Float, p: Float) = a + (b - a) * p
private fun phase(t: Float, a: Float, b: Float): Float {
  val p = ((t - a) / (b - a)).coerceIn(0f, 1f)
  return p * p * (3 - 2 * p)
}

private data class Terminal(val center: Offset, val width: Float, val angle: Float, val alpha: Float) {
  // Screen coordinate exported by Blender; transform it with the same phone pose.
  val signal: Offset get() {
    val x = (.44016f - .5f) * width
    val y = (.545346f - .5f) * width * 1.5f
    val a = angle * PI.toFloat() / 180
    return center + Offset(x * cos(a) - y * sin(a), x * sin(a) + y * cos(a))
  }
}

/** A full-resolution Blender layer and native geometry, all evaluated from the story clock. */
@Composable
internal fun OnboardingScene(time: () -> Float, reduced: Boolean, modifier: Modifier) {
  val source = imageResource(Res.drawable.onboarding_terminal)
  val terminal = remember(source) { source.withMipmaps() }
  val red = Theme.colors.accent
  val textMeasurer = rememberTextMeasurer()
  val labelStyle = Theme.type.overline
  val seekerLabel = stringResource(Res.string.story_seeker)
  val phoneLabel = stringResource(Res.string.story_any_phone)
  val lockedLabel = stringResource(Res.string.story_locked)
  Canvas(modifier.clearAndSetSemantics {}) {
    val t = time()
    val factor = minOf(size.width, size.height) / 360f
    translate((size.width - 360 * factor) / 2, (size.height - 360 * factor) / 2) {
      scale(factor, factor, Offset.Zero) {
        fun label(text: String, at: Offset, alpha: Float) {
          if (alpha < .01f) return
          val measured = textMeasurer.measure(text, labelStyle.copy(fontSize = (11 / density).sp))
          drawText(measured, Color.White.copy(alpha = alpha.coerceIn(0f, 1f)),
            at - Offset(measured.size.width / 2f, 0f))
        }
        val intro = phase(t, .05f, .7f)
        val join = phase(t, 3.65f, 5.25f)
        val choice = phase(t, 7.55f, 8.65f)
        val playing = phase(t, 10.5f, 11.5f)
        val complete = phase(t, 17.6f, 19.1f)
        val handoff = phase(t, 21.7f, 22.8f)
        val calm = if (reduced) 0f else sin(t * 1.25f) * 3
        val presence = (1 - complete) * .98f
        val phoneAlpha = 1 - choice * (1 - playing) * .66f
        fun phone(x: Float, y: Float, w: Float, angle: Float, alpha: Float) = Terminal(
          Offset(blend(x, 180f, complete), blend(y + calm, 180f, complete)),
          w * (1 - complete * .4f), angle * (1 - complete), alpha * phoneAlpha * presence)
        val phones = listOf(
          phone(blend(180f, 69f, join), blend(176f, 209f, join), blend(166f, 90f, join), blend(-3f, -11f, join), intro),
          phone(blend(392f, 291f, join), blend(255f, 205f, join), blend(60f, 90f, join), blend(25f, 8f, join), phase(t, 3.9f, 4.7f)),
          phone(180f, blend(-120f, 109f, join), blend(56f, 83f, join), blend(-18f, 0f, join), phase(t, 4.4f, 5.2f)),
        )
        phones.forEachIndexed { i, p ->
          // Integer destinations here would snap to scene units, several pixels apart once scaled.
          if (p.alpha > .005f) rotate(p.angle, p.center) {
            translate(p.center.x - p.width / 2, p.center.y - p.width * .75f) {
              scale(p.width / terminal.width, Offset.Zero) {
                drawImage(terminal, dstSize = IntSize(terminal.width, terminal.height), alpha = p.alpha,
                  filterQuality = FilterQuality.Medium)
              }
            }
          }
          label(if (i == 0) seekerLabel else phoneLabel,
            p.center + Offset(0f, p.width * .69f + 15),
            (if (i == 0) intro else join * .7f) * (1 - choice) * (1 - complete))
        }
        val source = phones[0].signal
        glow(source, red, phase(t, .6f, 1.3f) * presence * phoneAlpha, 17f)
        if (join > 0 && playing < 1) {
          phones.drop(1).forEachIndexed { i, p ->
            route(source, p.signal, red.copy(alpha = .14f * join * (1 - playing)), if (i == 0) 0f else -15f)
            packet(source, p.signal, (t - (4.2f + i * .72f)) / .65f, red)
          }
        }

        // Abstract portals teach the catalogue without promising particular games.
        val cards = choice * (1 - phase(t, 10.4f, 11.6f))
        if (cards > .001f) {
          val fan = phase(t, 7.7f, 8.8f)
          val selected = phase(t, 9.1f, 10.3f)
          val unlocked = phase(t, 8.9f, 9.6f)
          portal(Offset(blend(180f, 103f, fan), blend(210f, 196f, fan)), 61f, -10f * fan, 0, unlocked, cards, red)
          portal(Offset(blend(180f, 257f, fan), blend(210f, 196f, fan)), 61f, 10f * fan, 1, unlocked, cards, red)
          portal(Offset(180f, blend(211f, 199f, fan)), blend(74f, 92f, selected), 0f, 2, unlocked, cards, red)
          route(source, Offset(180f, 199f), red.copy(alpha = .2f * cards), -12f)
          packet(source, Offset(180f, 199f), (t - 8.25f) / .65f, red)
          label(lockedLabel, Offset(180f, 275f), cards * (1 - unlocked))
        }

        val arena = Offset(180f, 231f)
        val assembly = phase(t, 11.25f, 12.2f)
        drawCircle(red.copy(alpha = .14f * assembly * (1 - complete)), 54f,
          arena, style = Stroke(.8f))
        var receive = 0f
        StoryTimeline.cues.filter { it.second == xyz.mcxross.formation.platform.SoundCue.STORY_BEAT }
          .forEachIndexed { i, cue ->
            val p = (t - cue.first) / .62f
            if (p in 0f..1f) {
              packet(phones[i % 3].signal, arena, p, red)
              receive = maxOf(receive, sin(p * PI.toFloat()))
            }
          }
        val settle = phase(t, 17.5f, 18.4f)
        for (i in 0..2) {
          val part = phase(t, 11.3f + i * .3f, 12.05f + i * .3f)
          val float = if (reduced) 0f else sin(t * 1.7f + i * 2) * 4 * (1 - settle)
          val initialY = listOf(233f, 217f, 243f)[i]
          val target = Offset(153f + i * 27f, blend(blend(initialY, 226f, settle), 171f, handoff) + float)
          val origin = phones[i].signal
          val at = Offset(blend(origin.x, target.x, part), blend(origin.y, target.y, part))
          val h = (if (i == 1) 54f else 44f) + receive * 3
          if (part > 0) {
            glow(at, red, part * (.08f + receive * .12f), 35f)
            rotate(listOf(-17f, 14f, -9f)[i] * (1 - settle), at) {
              drawRoundRect((if (i == 2) red else Color.White).copy(alpha = part),
                at - Offset(8.5f, h / 2), Size(17f, h), CornerRadius(2f))
            }
          }
        }
        val wave = phase(t, 18.35f, 19.4f)
        val waveAlpha = phase(t, 18.35f, 18.6f) * (1 - phase(t, 19f, 20.2f))
        drawCircle(red.copy(alpha = waveAlpha * .5f), 68f + wave * 45f, arena, style = Stroke(1f))
      }
    }
  }
}

private fun DrawScope.glow(at: Offset, color: Color, alpha: Float, radius: Float) {
  if (alpha <= 0f) return
  drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha * .5f), Color.Transparent), at, radius), radius, at)
  drawCircle(color.copy(alpha = alpha), 2.4f, at)
  drawCircle(Color.White.copy(alpha = alpha), .8f, at)
}

private fun controls(from: Offset, to: Offset, bend: Float) = Pair(
  Offset(blend(from.x, to.x, .33f) + bend, from.y - 34),
  Offset(blend(from.x, to.x, .7f) + bend, to.y + 20))

private fun DrawScope.route(from: Offset, to: Offset, color: Color, bend: Float = 0f) {
  val (b, c) = controls(from, to, bend)
  drawPath(Path().apply { moveTo(from.x, from.y); cubicTo(b.x, b.y, c.x, c.y, to.x, to.y) }, color, style = Stroke(.7f))
}

private fun DrawScope.packet(from: Offset, to: Offset, p: Float, red: Color) {
  if (p !in 0f..1f) return
  val (b, c) = controls(from, to, 0f)
  fun sample(q: Float): Offset {
    val v = 1 - q
    return from * (v * v * v) + b * (3 * v * v * q) + c * (3 * v * q * q) + to * (q * q * q)
  }
  val at = sample(p)
  drawLine(red.copy(alpha = .65f), sample((p - .12f).coerceAtLeast(0f)), at, 1.5f, StrokeCap.Round)
  glow(at, red, sin(p * PI.toFloat()).coerceAtLeast(0f), 19f)
}

private fun DrawScope.portal(at: Offset, width: Float, angle: Float, kind: Int, unlocked: Float, alpha: Float, red: Color) {
  rotate(angle, at) {
    val h = width * 1.28f
    drawRoundRect(Color(0xFF111418).copy(alpha = alpha), at - Offset(width / 2, h / 2), Size(width, h), CornerRadius(7f))
    drawRoundRect(red.copy(alpha = alpha * (.2f + unlocked * .55f)), at - Offset(width / 2, h / 2), Size(width, h),
      CornerRadius(7f), style = Stroke(.8f))
    val color = red.copy(alpha = alpha * (.12f + .88f * unlocked))
    when (kind) {
      0 -> { drawCircle(color, width * .22f, at, style = Stroke(2f)); drawCircle(color, 3f, at) }
      1 -> {
        val a = at + Offset(-13f, 12f); val b = at + Offset(12f, -12f)
        drawLine(color, a, b, 2f); drawCircle(color, 4f, a); drawCircle(color, 4f, b)
      }
      else -> for (i in 0..2) {
        val barHeight = if (i == 1) 32f else 25f
        drawRoundRect(color, at + Offset((i - 1) * 14f - 4f, -barHeight / 2), Size(8f, barHeight), CornerRadius(1f))
      }
    }
  }
}
