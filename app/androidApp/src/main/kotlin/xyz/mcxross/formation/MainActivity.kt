package xyz.mcxross.formation

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.CompletableDeferred
import xyz.mcxross.formation.platform.ActivityBridge
import xyz.mcxross.formation.ui.FormationApp

class MainActivity : ComponentActivity(), ActivityBridge {
  override lateinit var walletSender: ActivityResultSender
  private var pendingPermissions: CompletableDeferred<Map<String, Boolean>>? = null
  private val permissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
      pendingPermissions?.complete(result)
      pendingPermissions = null
    }

  private val app: FormationApplication
    get() = application as FormationApplication

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    super.onCreate(savedInstanceState)
    walletSender = ActivityResultSender(this)
    app.platform.bridge = this
    if (savedInstanceState == null) open(intent)
    setContent { FormationApp(app.graph) }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    open(intent)
  }

  override fun onResume() {
    super.onResume()
    app.platform.bridge = this
    app.platform.setAudioActive(true)
  }

  override fun onPause() {
    app.platform.setAudioActive(false)
    super.onPause()
  }

  override fun onDestroy() {
    if (app.platform.bridge === this) app.platform.bridge = null
    super.onDestroy()
  }

  private fun open(intent: Intent?) {
    intent?.data?.toString()?.let(app.graph::open)
  }

  override fun keepScreenOn(on: Boolean) = runOnUiThread {
    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  override suspend fun requestPermissions(permissions: Array<String>): Map<String, Boolean> {
    val deferred = CompletableDeferred<Map<String, Boolean>>()
    pendingPermissions?.complete(emptyMap())
    pendingPermissions = deferred
    permissionLauncher.launch(permissions)
    return deferred.await()
  }
}
