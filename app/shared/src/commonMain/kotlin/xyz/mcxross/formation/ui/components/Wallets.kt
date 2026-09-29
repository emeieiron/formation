package xyz.mcxross.formation.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import xyz.mcxross.formation.platform.PlatformServices

class WalletApp(val name: String, val androidPackage: String) {
  val storeUrl: String
    get() = "https://play.google.com/store/apps/details?id=$androidPackage"
}

// Wallets that speak Mobile Wallet Adapter on Android; the first is the one we suggest.
val MwaWallets =
  listOf(WalletApp("Solflare", "com.solflare.mobile"), WalletApp("Phantom", "app.phantom"))

// Re-checked while shown, so coming back from the store after installing flips it without a
// restart.
@Composable
fun rememberWalletInstalled(platform: PlatformServices): Boolean =
  produceState(platform.wallet.installed(), platform) {
      while (true) {
        value = platform.wallet.installed()
        delay(1_500)
      }
    }
    .value
