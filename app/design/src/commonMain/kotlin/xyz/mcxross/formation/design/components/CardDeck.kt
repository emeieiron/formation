package xyz.mcxross.formation.design.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import xyz.mcxross.formation.design.tokens.Motion
import xyz.mcxross.formation.design.tokens.Space

@Composable
fun CardDeck(
  state: PagerState,
  key: (Int) -> Any,
  modifier: Modifier = Modifier,
  content: @Composable (page: Int, modifier: Modifier) -> Unit,
) {
  val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f
  val moves = motionEnabled()
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val cardWidth = (maxWidth - Space.gutter * 2 - 40.dp).coerceAtMost(340.dp)
    HorizontalPager(
      state = state,
      modifier = Modifier.fillMaxWidth(),
      pageSize = PageSize.Fixed(cardWidth),
      contentPadding =
        PaddingValues(start = Space.gutter, end = maxWidth - cardWidth - Space.gutter),
      pageSpacing = Space.m,
      beyondViewportPageCount = 2,
      verticalAlignment = Alignment.CenterVertically,
      flingBehavior = PagerDefaults.flingBehavior(state, snapAnimationSpec = Motion.snappy()),
      key = key,
    ) { page ->
      content(
        page,
        Modifier.fillMaxWidth().zIndex(2f - abs(state.currentPage - page)).graphicsLayer {
          val offset = state.currentPage - page + state.currentPageOffsetFraction
          val depth = abs(offset).coerceAtMost(2f)
          transformOrigin = TransformOrigin(if (direction > 0) 0f else 1f, 0.5f)
          scaleX = 1f - depth * 0.065f
          scaleY = scaleX
          alpha = 1f - depth * 0.22f
          rotationY = if (moves) offset.coerceIn(-1f, 1f) * -3f else 0f
          cameraDistance = 18f * density
          // Keep previous cards in a shallow stack while future cards enter from the edge.
          if (offset > 0f) {
            translationX =
              direction *
                (offset * (cardWidth + Space.m).toPx() - offset.coerceAtMost(2f) * 12.dp.toPx())
          }
        },
      )
    }
  }
}
