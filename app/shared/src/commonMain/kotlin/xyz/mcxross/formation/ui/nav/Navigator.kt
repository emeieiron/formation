package xyz.mcxross.formation.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface Screen {
  data object Home : Screen

  data object Session : Screen

  data object Rewards : Screen

  data object Recovery : Screen

  data object Settings : Screen
}

class Entry(val screen: Screen) {
  val key: String = "$screen#${counter++}"

  private companion object {
    var counter = 0L
  }
}

class Navigator {
  var stack by mutableStateOf(listOf(Entry(Screen.Home)))
    private set

  val current: Entry
    get() = stack.last()

  val canGoBack: Boolean
    get() = stack.size > 1

  var forward by mutableStateOf(true)
    private set

  fun push(screen: Screen) {
    if (current.screen == screen) return
    forward = true
    stack = stack + Entry(screen)
  }

  fun pop(): Boolean {
    if (!canGoBack) return false
    forward = false
    stack = stack.dropLast(1)
    return true
  }

  fun remove(screen: Screen) {
    if (stack.none { it.screen == screen }) return
    forward = false
    stack = stack.filterNot { it.screen == screen }.ifEmpty { listOf(Entry(Screen.Home)) }
  }

  fun home() {
    forward = false
    stack = listOf(stack.first())
  }
}
