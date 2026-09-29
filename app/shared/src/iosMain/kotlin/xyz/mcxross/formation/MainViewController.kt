package xyz.mcxross.formation

import androidx.compose.ui.window.ComposeUIViewController
import xyz.mcxross.formation.platform.IosPlatform
import xyz.mcxross.formation.state.AppGraph
import xyz.mcxross.formation.ui.FormationApp

private val graph by lazy { AppGraph(IosPlatform()) }

@Suppress("FunctionName", "unused")
fun MainViewController() = ComposeUIViewController { FormationApp(graph) }
