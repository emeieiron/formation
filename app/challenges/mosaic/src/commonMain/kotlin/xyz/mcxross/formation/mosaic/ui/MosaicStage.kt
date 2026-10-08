package xyz.mcxross.formation.mosaic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import xyz.mcxross.formation.challenge.StageScope
import xyz.mcxross.formation.challenge.rememberHostNow
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.mosaic.Artwork
import xyz.mcxross.formation.mosaic.Edge
import xyz.mcxross.formation.mosaic.MosaicState
import xyz.mcxross.formation.mosaic.Pinch
import xyz.mcxross.formation.mosaic.SeamOutcome

@Composable
internal fun MosaicStage(scope: StageScope<MosaicState, Pinch>) {
  val state = scope.state
  val profile = scope.screen
  val position = state.position(scope.me)
  if (profile == null || position < 0) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("This phone's screen isn't measured", color = Theme.colors.contentSecondary)
    }
    return
  }
  val piece =
    remember(state.grid, state.fragment, state.gaps, profile, position) {
      Piece(state, profile, position)
    }
  val now = rememberHostNow(scope.clock)
  val sent = remember(scope.round) { mutableStateListOf<Pinch>() }
  val colors = Theme.colors
  MosaicFeedback(scope)
  Oriented(state.grid.placement, Modifier.fillMaxSize().background(Color.Black)) {
    Box(
      Modifier.fillMaxSize()
        .semantics { contentDescription = "Mosaic piece: ${pieceLabel(state, position)}" }
        .pinchInput(piece, state.grid, scope.clock) { pinch ->
          sent += pinch
          if (sent.size > 4) sent.removeAt(0)
          scope.send(pinch)
        }
        .drawBehind {
          val current = scope.state
          val at = scope.clock.hostNow()
          // Reading the frame clock redraws every frame, so only do it while something animates.
          val time = if (animating(current, sent, at)) now.value else at
          drawPiece(current, piece, time, sent, colors.accent, colors.warning)
        }
    ) {
      MosaicHud(state, piece, now)
      SeamTargets(state, piece)
    }
  }
}

private fun animating(state: MosaicState, sent: List<Pinch>, at: Long): Boolean {
  val finished = state.finishedAt
  return (finished != null && state.won && at - finished <= REVEAL_MS) ||
    state.events.lastOrNull()?.let { at - it.at <= FLASH_MS } == true ||
    sent.any { at - it.upAt <= PENDING_MS }
}

private fun DrawScope.drawPiece(
  state: MosaicState,
  piece: Piece,
  now: Long,
  sent: List<Pinch>,
  accent: Color,
  warning: Color,
) {
  clipRect(piece.rect.left, piece.rect.top, piece.rect.right, piece.rect.bottom) {
    translate(piece.artworkOrigin.x, piece.artworkOrigin.y) {
      scale(piece.artworkScale, piece.artworkScale, Offset.Zero) {
        drawPath(Mark.path, Mark.brush)
        val finished = state.finishedAt
        val sweep =
          if (finished != null && state.won) (now - finished) / REVEAL_MS.toFloat() else -1f
        // Every phone places the band by the shared clock, so it crosses the whole table.
        if (sweep in 0f..1f)
          clipPath(Mark.path) {
            val x = -SWEEP_WIDTH + sweep * (Artwork.WIDTH.toFloat() + SWEEP_WIDTH * 2)
            drawRect(
              Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = 0.8f),
                1f to Color.Transparent,
                startX = x - SWEEP_WIDTH,
                endX = x + SWEEP_WIDTH,
              ),
              Offset(x - SWEEP_WIDTH, 0f),
              Size(SWEEP_WIDTH * 2, Artwork.HEIGHT.toFloat()),
            )
          }
      }
    }
  }
  val recent = state.events.lastOrNull()?.takeIf { now - it.at in 0..FLASH_MS }
  state.seams.forEach { seam ->
    val edge = seam.edgeOf(piece.position) ?: return@forEach
    val (from, to) = piece.edgeLine(edge)
    val misaligned = recent?.outcome == SeamOutcome.Misaligned && recent.seam == seam.id
    when {
      seam.id in state.sealed -> {
        drawLine(accent.copy(alpha = 0.28f), from, to, 14.dp.toPx(), StrokeCap.Round)
        drawLine(accent, from, to, 4.dp.toPx(), StrokeCap.Round)
      }
      misaligned -> drawLine(warning, from, to, 4.dp.toPx(), StrokeCap.Round)
      state.unsealedMarks -> {
        val middle = Offset((from.x + to.x) / 2, (from.y + to.y) / 2)
        val half = Offset((to.x - from.x) * 0.12f, (to.y - from.y) * 0.12f)
        drawLine(
          Color.White.copy(alpha = 0.35f),
          middle - half,
          middle + half,
          3.dp.toPx(),
          StrokeCap.Round,
        )
      }
    }
  }
  // A short-lived tick shows where this phone's half landed while the Seeker pairs it.
  sent.forEach { pinch ->
    val age = now - pinch.upAt
    if (age !in 0..PENDING_MS) return@forEach
    val alpha = 1f - age / PENDING_MS.toFloat()
    val along = piece.px(pinch.alongMm)
    val at =
      when (pinch.edge) {
        Edge.Left -> Offset(piece.rect.left, piece.rect.top + along)
        Edge.Right -> Offset(piece.rect.right, piece.rect.top + along)
        Edge.Top -> Offset(piece.rect.left + along, piece.rect.top)
        Edge.Bottom -> Offset(piece.rect.left + along, piece.rect.bottom)
      }
    drawCircle(
      Color.White.copy(alpha = 0.6f * alpha),
      10.dp.toPx() * (1.4f - alpha * 0.4f),
      at,
      style = Stroke(2.dp.toPx()),
    )
  }
  if (recent?.outcome == SeamOutcome.Wrong) {
    val alpha = 1f - (now - recent.at) / FLASH_MS.toFloat()
    drawRect(
      accent.copy(alpha = 0.7f * alpha),
      piece.rect.topLeft,
      piece.rect.size,
      style = Stroke(10.dp.toPx()),
    )
  }
}

@Composable
private fun MosaicHud(state: MosaicState, piece: Piece, now: State<Long>) {
  val strip = state.canvas.quietStrip(piece.position)
  val density = LocalDensity.current
  val left = piece.px(piece.box.left + strip.left)
  val top = piece.px(piece.box.top + strip.top)
  val width = with(density) { piece.px(strip.width).toDp() }
  val height = with(density) { piece.px(strip.height).toDp() }
  // Derived so the HUD recomposes once a second rather than every frame.
  val seconds by
    remember(state) {
      derivedStateOf {
        ((state.endsAt - (state.finishedAt ?: now.value)).coerceAtLeast(0) + 999) / 1_000
      }
    }
  val recent by
    remember(state) {
      derivedStateOf { state.events.lastOrNull()?.takeIf { now.value - it.at <= HINT_MS } }
    }
  Box(
    Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }.size(width, height),
    contentAlignment = Alignment.Center,
  ) {
    val colors = Theme.colors
    val time = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    val last = recent
    val note =
      when {
        last?.outcome == SeamOutcome.Misaligned &&
          last.seam in state.seams.filter { it.edgeOf(piece.position) != null }.map { it.id } ->
          "Line up the bars"
        last?.outcome == SeamOutcome.Wrong -> "Wrong pair"
        state.labels -> pieceLabel(state, piece.position)
        else -> ""
      }
    val misses = "●".repeat(MosaicState.MAX_MISSES - state.misses) + "○".repeat(state.misses)
    Text(
      "$time   $note   ${state.sealed.size}/${state.seams.size}  $misses",
      maxLines = 1,
      textAlign = TextAlign.Center,
      style = Theme.type.caption,
      color =
        if (seconds <= 10 && state.finishedAt == null) colors.negative else colors.contentSecondary,
      modifier =
        Modifier.semantics {
          contentDescription =
            "$seconds seconds left. ${state.sealed.size} of ${state.seams.size} seams sealed. " +
              "${state.misses} wrong pairs. $note"
        },
    )
  }
}

// Invisible strips along each seam edge, for screen readers and the emulator driver.
@Composable
private fun SeamTargets(state: MosaicState, piece: Piece) {
  val density = LocalDensity.current
  val band = piece.px(TARGET_MM)
  state.seams.forEach { seam ->
    val edge = seam.edgeOf(piece.position) ?: return@forEach
    val rect = piece.rect
    val (x, y, w, h) =
      when (edge) {
        Edge.Left -> listOf(rect.left, rect.top, band, rect.height)
        Edge.Right -> listOf(rect.right - band, rect.top, band, rect.height)
        Edge.Top -> listOf(rect.left, rect.top, rect.width, band)
        Edge.Bottom -> listOf(rect.left, rect.bottom - band, rect.width, band)
      }
    Box(
      Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
        .size(with(density) { w.toDp() }, with(density) { h.toDp() })
        .semantics {
          contentDescription =
            "${edge.name} seam, ${if (seam.id in state.sealed) "sealed" else "open"}"
        }
    )
  }
}

private const val REVEAL_MS = 1_000L
private const val SWEEP_WIDTH = 14f
private const val FLASH_MS = 600L
private const val PENDING_MS = 700L
private const val HINT_MS = 1_600L
private const val TARGET_MM = 8.0
