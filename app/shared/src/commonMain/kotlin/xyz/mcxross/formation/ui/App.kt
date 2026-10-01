package xyz.mcxross.formation.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import xyz.mcxross.formation.design.FormationTheme
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.BackHandler
import xyz.mcxross.formation.design.components.OverlayHost
import xyz.mcxross.formation.design.tokens.Motion
import xyz.mcxross.formation.state.AppGraph
import xyz.mcxross.formation.ui.nav.Screen
import xyz.mcxross.formation.ui.screens.HomeScreen
import xyz.mcxross.formation.ui.screens.RewardsScreen
import xyz.mcxross.formation.ui.screens.RecoveryScreen
import xyz.mcxross.formation.state.recovery.ClaimKeyState
import xyz.mcxross.formation.ui.screens.SettingsScreen
import xyz.mcxross.formation.ui.screens.WelcomeScreen
import xyz.mcxross.formation.ui.session.SessionScreen

val LocalGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }

@Composable
fun FormationApp(graph: AppGraph) {
  FormationTheme {
    CompositionLocalProvider(LocalGraph provides graph) {
      val profile by graph.identity.profile.collectAsState()
      val claims by graph.identity.claims.status.collectAsState()
      val recoveryProblem by graph.recoveryProblem.collectAsState()
      OverlayHost(Modifier.background(Theme.colors.background)) {
        if (claims is ClaimKeyState.Missing || recoveryProblem != null) RecoveryScreen()
        else if (profile == null)
          WelcomeScreen(
            onDone = {
              graph.identity.save(it)
              graph.resumePendingJoin()
            }
          )
        else Main(graph)
      }
    }
  }
}

@Composable
private fun Main(graph: AppGraph) {
  val nav = graph.navigator
  val travel = with(LocalDensity.current) { 12.dp.roundToPx() }
  BackHandler(enabled = nav.canGoBack) { nav.pop() }
  AnimatedContent(
    targetState = nav.current,
    transitionSpec = {
      val direction = if (nav.forward) 1 else -1
      (fadeIn(Motion.standard()) +
        slideInHorizontally(Motion.standard()) { direction * travel }) togetherWith
        (fadeOut(Motion.exit()) + slideOutHorizontally(Motion.exit()) { -direction * travel })
    },
    contentKey = { it.key },
    label = "screen",
  ) { entry ->
    when (entry.screen) {
      Screen.Home -> HomeScreen()
      Screen.Session -> SessionScreen()
      Screen.Rewards -> RewardsScreen()
      Screen.Settings -> SettingsScreen()
      Screen.Recovery -> RecoveryScreen(onBack = { graph.navigator.pop() })
    }
  }
}
