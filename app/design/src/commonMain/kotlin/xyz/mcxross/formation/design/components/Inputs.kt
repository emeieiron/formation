package xyz.mcxross.formation.design.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.design.LocalRaised
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.tokens.Motion
import xyz.mcxross.formation.design.tokens.Shapes
import xyz.mcxross.formation.design.tokens.Sizes
import xyz.mcxross.formation.design.tokens.Space

@Composable
fun TextField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String? = null,
  label: String? = null,
  leadingIcon: ImageVector? = null,
  singleLine: Boolean = true,
  maxLength: Int? = null,
  keyboardOptions: KeyboardOptions =
    KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
  keyboardActions: KeyboardActions = KeyboardActions.Default,
  textStyle: TextStyle = Theme.type.bodyStrong,
  autoFocus: Boolean = false,
  trailing: (@Composable () -> Unit)? = null,
) {
  val c = Theme.colors
  val source = remember { MutableInteractionSource() }
  val focused by source.collectIsFocusedAsState()
  val border by
    animateColorAsState(
      if (focused) c.accent.copy(alpha = 0.8f) else c.line,
      Motion.standard(),
      label = "field-border",
    )
  val focus = remember { FocusRequester() }
  if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
  Column(modifier.fillMaxWidth()) {
    label?.let {
      Overline(it, Modifier.padding(start = 4.dp, bottom = Space.s))
    }
    BasicTextField(
      value = value,
      onValueChange = { next ->
        onValueChange(if (maxLength != null) next.take(maxLength) else next)
      },
      modifier = Modifier.fillMaxWidth().focusRequester(focus),
      singleLine = singleLine,
      textStyle = textStyle.copy(color = c.content),
      cursorBrush = SolidColor(c.accent),
      keyboardOptions = keyboardOptions,
      keyboardActions = keyboardActions,
      interactionSource = source,
      decorationBox = { inner ->
        Row(
          Modifier.fillMaxWidth()
            .heightIn(min = Sizes.controlLarge)
            .clip(Shapes.field)
            .background(if (LocalRaised.current) c.surfaceHigher else c.surfaceHigh)
            .border(Sizes.hairline, border, Shapes.field)
            .padding(horizontal = Space.l),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          leadingIcon?.let {
            Icon(it, null, tint = if (focused) c.content else c.contentTertiary, size = Sizes.iconM)
            Spacer(Modifier.width(Space.m))
          }
          Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
            if (value.isEmpty() && placeholder != null) {
              Text(placeholder, style = textStyle, color = c.contentTertiary, maxLines = 1)
            }
            inner()
          }
          trailing?.let {
            Spacer(Modifier.width(Space.s))
            it()
          }
        }
      },
    )
  }
}

@Composable
fun CodeField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  length: Int = 4,
  alphabet: String = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789",
  onDone: () -> Unit = {},
) {
  val c = Theme.colors
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
  BasicTextField(
    value = value,
    onValueChange = { raw ->
      val cleaned = raw.uppercase().filter { it in alphabet }.take(length)
      onValueChange(cleaned)
      if (cleaned.length == length) onDone()
    },
    modifier = modifier.focusRequester(focus),
    singleLine = true,
    cursorBrush = SolidColor(Color.Transparent),
    textStyle = Theme.type.code.copy(color = Color.Transparent),
    keyboardOptions =
      KeyboardOptions(
        capitalization = KeyboardCapitalization.Characters,
        keyboardType = KeyboardType.Ascii,
        autoCorrectEnabled = false,
        imeAction = ImeAction.Go,
      ),
    keyboardActions = KeyboardActions(onGo = { onDone() }),
    decorationBox = {
      Row(
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        repeat(length) { i ->
          val ch = value.getOrNull(i)
          val active = i == value.length
          val border by
            animateColorAsState(
              if (active) c.accent else if (ch != null) c.lineStrong else c.line,
              Motion.standard(),
              label = "code$i",
            )
          val lift by
            animateDpAsState(if (ch != null) (-2).dp else 0.dp, Motion.bouncy(), label = "lift$i")
          Box(
            Modifier.offset(y = lift)
              .size(width = 60.dp, height = 72.dp)
              .clip(Shapes.control)
              .background(c.surfaceHigh)
              .border(if (active) 1.5.dp else Sizes.hairline, border, Shapes.control),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              ch?.toString() ?: "",
              style = Theme.type.numeral,
              color = c.content,
              textAlign = TextAlign.Center,
            )
          }
        }
      }
    },
  )
}

@Composable
fun Toggle(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val c = Theme.colors
  val track by
    animateColorAsState(
      if (checked) c.accent else c.surfaceHigher,
      Motion.standard(),
      label = "track",
    )
  val x by animateDpAsState(if (checked) 22.dp else 2.dp, Motion.snappy(), label = "thumb")
  Box(
    modifier
      .size(width = 50.dp, height = 30.dp)
      .clip(Shapes.pill)
      .background(if (enabled) track else c.surfaceHigh)
      .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
  ) {
    Box(
      Modifier.offset(x = x, y = 2.dp)
        .size(26.dp)
        .clip(Shapes.circle)
        .background(if (enabled) Color.White else c.contentDisabled)
    )
  }
}

@Composable
fun SettingRow(
  title: String,
  modifier: Modifier = Modifier,
  detail: String? = null,
  icon: ImageVector? = null,
  trailing: @Composable () -> Unit = {},
) {
  val c = Theme.colors
  Row(
    modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = Space.l, vertical = Space.m),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    icon?.let {
      IconTile(it, tint = c.content, size = 36.dp, background = c.surfaceHigher)
      Spacer(Modifier.width(Space.m))
    }
    Column(Modifier.weight(1f)) {
      Text(title, style = Theme.type.bodyStrong, maxLines = 1)
      detail?.let {
        Spacer(Modifier.height(2.dp))
        Text(it, style = Theme.type.footnote, color = c.contentSecondary)
      }
    }
    Spacer(Modifier.width(Space.m))
    trailing()
  }
}
