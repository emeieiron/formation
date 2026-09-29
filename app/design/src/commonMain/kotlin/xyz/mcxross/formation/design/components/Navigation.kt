package xyz.mcxross.formation.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Sizes
import xyz.mcxross.formation.design.tokens.Space

@Composable
fun TopBar(
  modifier: Modifier = Modifier,
  title: String? = null,
  onBack: (() -> Unit)? = null,
  backIcon: ImageVector = Icons.ArrowLeft,
  actions: @Composable RowScope.() -> Unit = {},
) {
  Row(
    modifier.fillMaxWidth().height(Sizes.topBar).padding(horizontal = Space.s),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (onBack != null) IconButton(backIcon, "Back", onBack, style = IconButtonStyle.Ghost)
    else Spacer(Modifier.width(Space.m))
    Box(Modifier.weight(1f).padding(horizontal = Space.xs)) {
      title?.let { Text(it, style = Theme.type.headline, maxLines = 1) }
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Space.xs),
      content = actions,
    )
  }
}

@Composable
fun Page(
  modifier: Modifier = Modifier,
  background: @Composable BoxScope.() -> Unit = {},
  topBar: @Composable () -> Unit = {},
  bottomBar: @Composable () -> Unit = {},
  content: @Composable ColumnScope.() -> Unit,
) {
  Box(modifier.fillMaxSize().background(Theme.colors.background)) {
    background()
    Column(Modifier.fillMaxSize().imePadding()) {
      Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
      topBar()
      Column(Modifier.weight(1f).fillMaxWidth(), content = content)
      bottomBar()
    }
  }
}

@Composable
fun BottomActions(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  val c = Theme.colors
  Column(
    modifier
      .fillMaxWidth()
      .background(Brush.verticalGradient(0f to Color.Transparent, 0.28f to c.background))
      .padding(start = Space.gutter, end = Space.gutter, top = Space.xxl, bottom = Space.m)
      .navigationBarsPadding(),
    verticalArrangement = Arrangement.spacedBy(Space.s),
    content = content,
  )
}

@Composable
fun NavigationBarSpacer() {
  Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
  NavigationBackHandler(
    rememberNavigationEventState(NavigationEventInfo.None),
    isBackEnabled = enabled,
  ) {
    onBack()
  }
}

@Composable
fun SectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  action: String? = null,
  onAction: (() -> Unit)? = null,
) {
  Row(
    modifier
      .fillMaxWidth()
      .padding(start = Space.gutter, end = Space.s, top = Space.xl, bottom = Space.s),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Overline(title, Modifier.weight(1f))
    if (action != null && onAction != null) TextButton(action, onAction)
    else Spacer(Modifier.height(36.dp))
  }
}
