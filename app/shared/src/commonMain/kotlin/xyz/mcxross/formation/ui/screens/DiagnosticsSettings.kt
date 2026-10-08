package xyz.mcxross.formation.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import xyz.mcxross.formation.design.components.*
import xyz.mcxross.formation.design.foundation.Panel
import xyz.mcxross.formation.design.icons.Icons
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.ui.LocalGraph

@Composable
internal fun DiagnosticsSettings() {
  val graph = LocalGraph.current
  val entries by graph.diagnostics.entries.collectAsState()
  SectionHeader("Diagnostics")
  Panel(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
    Column {
      SettingRow(
        "Local traces",
        detail =
          "${entries.size}/${xyz.mcxross.formation.state.diagnostics.LocalDiagnostics.LIMIT} events · stays on this phone",
        icon = Icons.Phone,
      )
      Row(Modifier.padding(Space.l), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Button(
          "Export",
          { graph.platform.external.share(graph.diagnostics.export()) },
          Modifier.weight(1f),
          style = ButtonStyle.Secondary,
        )
        Button(
          "Clear",
          { graph.diagnostics.clear() },
          Modifier.weight(1f),
          style = ButtonStyle.Ghost,
        )
      }
    }
  }
}
