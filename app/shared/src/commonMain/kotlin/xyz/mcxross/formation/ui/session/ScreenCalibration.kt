package xyz.mcxross.formation.ui.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.Button
import xyz.mcxross.formation.design.components.ButtonSize
import xyz.mcxross.formation.design.components.ButtonStyle
import xyz.mcxross.formation.design.components.ModalSheet
import xyz.mcxross.formation.design.components.SheetActions
import xyz.mcxross.formation.design.components.SheetHeader
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.platform.ScreenMeasurement
import xyz.mcxross.formation.platform.ScreenPort
import xyz.mcxross.formation.resources.*
import xyz.mcxross.formation.session.ScreenProfile

// Matches an on-screen outline to an ID-1 card, which is 53.98 mm wide, to recover pixels per millimetre.
@Composable
internal fun ScreenCalibration(screen: ScreenPort, onDismiss: () -> Unit) {
  val density = LocalDensity.current
  val start = remember {
    when (val now = screen.measurement.value) {
      is ScreenMeasurement.Measured -> now.profile.pxPerMm
      is ScreenMeasurement.NeedsCalibration -> now.estimate?.pxPerMm
      else -> null
    } ?: (density.density * 160.0 / MM_PER_INCH)
  }
  var pxPerMm by remember { mutableDoubleStateOf(start) }
  val c = Theme.colors
  ModalSheet(onDismiss) {
    SheetHeader(stringResource(Res.string.screen_calibration_title), subtitle = stringResource(Res.string.screen_calibration_body))
    val outlineHeight = with(density) { (CARD_SHOWN_MM * pxPerMm).toFloat().toDp() }
    Canvas(
      Modifier.fillMaxWidth().height(outlineHeight + 32.dp)
        .semantics { contentDescription = "Card outline. Drag to resize." }
        .pointerInput(Unit) {
          detectDragGestures { change, drag ->
            change.consume()
            pxPerMm = (pxPerMm + drag.x / CARD_WIDTH_MM).coerceIn(ScreenProfile.MIN_PX_PER_MM, ScreenProfile.MAX_PX_PER_MM)
          }
        }
    ) {
      val scale = pxPerMm.toFloat()
      val width = (CARD_WIDTH_MM * scale).toFloat()
      val height = (CARD_SHOWN_MM * scale).toFloat()
      val radius = (CARD_RADIUS_MM * scale).toFloat()
      val left = 24.dp.toPx()
      val top = 8.dp.toPx()
      drawRoundRect(
        Brush.verticalGradient(0f to c.accent.copy(alpha = 0.18f), 1f to Color.Transparent, startY = top, endY = top + height),
        Offset(left, top), Size(width, height), CornerRadius(radius),
      )
      drawRoundRect(c.accent, Offset(left, top), Size(width, height + radius * 2), CornerRadius(radius), style = Stroke(2.dp.toPx()))
      val mark = 14.dp.toPx()
      drawLine(c.content, Offset(left, top), Offset(left + mark, top), 3.dp.toPx())
      drawLine(c.content, Offset(left, top), Offset(left, top + mark), 3.dp.toPx())
      drawLine(c.content, Offset(left + width, top), Offset(left + width - mark, top), 3.dp.toPx())
      drawLine(c.content, Offset(left + width, top), Offset(left + width, top + mark), 3.dp.toPx())
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.xxl), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
      Button(stringResource(Res.string.screen_calibration_smaller), { pxPerMm = (pxPerMm * 0.996).coerceAtLeast(ScreenProfile.MIN_PX_PER_MM) },
        Modifier.weight(1f), style = ButtonStyle.Secondary, size = ButtonSize.Medium, fillWidth = true)
      Button(stringResource(Res.string.screen_calibration_larger), { pxPerMm = (pxPerMm * 1.004).coerceAtMost(ScreenProfile.MAX_PX_PER_MM) },
        Modifier.weight(1f), style = ButtonStyle.Secondary, size = ButtonSize.Medium, fillWidth = true)
    }
    SheetActions {
      Button(stringResource(Res.string.screen_calibration_save), {
        screen.calibrate(pxPerMm)
        onDismiss()
      })
      Button(stringResource(Res.string.screen_calibration_reset), {
        screen.calibrate(null)
        onDismiss()
      }, style = ButtonStyle.Ghost)
    }
  }
}

private const val MM_PER_INCH = 25.4
private const val CARD_WIDTH_MM = 53.98
private const val CARD_RADIUS_MM = 3.18
private const val CARD_SHOWN_MM = 30.0
