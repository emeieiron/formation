package xyz.mcxross.formation.overdrive

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.challenge.PlayerView
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.PlayerLight
import xyz.mcxross.formation.design.foundation.Icon
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.icons.Icons

@Composable
internal fun PartnerClue(player: PlayerView, dial: Dial, wave: Int, preview: Boolean) {
  val symbol = dial.clue ?: return
  val colors = Theme.colors
  Row(Modifier.fillMaxWidth().border(1.dp, colors.lineStrong, RoundedCornerShape(8.dp))
    .clearAndSetSemantics {
      contentDescription = "Wave $wave. Clue for ${player.name}: ${symbol.label}" +
        if (preview && dial.nextClue != null) ". Next: ${dial.nextClue.label}" else ""
    }.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    PlayerLight(player.name, player.light, size = 40.dp)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text("CALL TO", style = Theme.type.overline, color = colors.contentTertiary)
      Text(player.name, style = Theme.type.title3, maxLines = 1)
    }
    SymbolGlyph(symbol, Modifier.size(44.dp))
    if (preview && dial.nextClue != null) {
      Icon(Icons.ChevronRight, null, tint = colors.contentTertiary, size = 12.dp)
      SymbolGlyph(dial.nextClue, Modifier.size(22.dp))
    }
  }
}
