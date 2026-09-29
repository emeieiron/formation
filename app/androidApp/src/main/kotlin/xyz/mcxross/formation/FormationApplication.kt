package xyz.mcxross.formation

import android.app.Application
import xyz.mcxross.formation.platform.AndroidPlatform
import xyz.mcxross.formation.state.AppGraph

class FormationApplication : Application() {
  val platform by lazy { AndroidPlatform(this) }

  val graph by lazy { AppGraph(platform) }
}
