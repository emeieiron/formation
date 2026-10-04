package xyz.mcxross.formation.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.mcxross.formation.challenge.ChallengeInfo
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.Overline
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.tokens.Motion
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.resources.Res
import xyz.mcxross.formation.resources.label_how_to_play

@Composable
internal fun GameGuide(info: ChallengeInfo) {
  Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.l)) {
    Overline(stringResource(Res.string.label_how_to_play))
    Spacer(Modifier.height(Space.m))
    AnimatedContent(info, contentKey = { it.id },
      transitionSpec = { fadeIn(Motion.standard()) togetherWith fadeOut(Motion.exit()) },
      label = "gameInstructions") { selected ->
      Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        selected.steps.forEach { step ->
          Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.Top) {
            Icon(step.icon, null, size = 18.dp, tint = Theme.colors.contentSecondary)
            Text(step.text, Modifier.weight(1f), style = Theme.type.footnote, color = Theme.colors.contentSecondary)
          }
        }
      }
    }
  }
}
