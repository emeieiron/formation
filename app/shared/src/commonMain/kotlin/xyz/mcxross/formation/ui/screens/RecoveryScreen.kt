package xyz.mcxross.formation.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import xyz.mcxross.formation.design.Theme
import xyz.mcxross.formation.design.components.*
import xyz.mcxross.formation.design.foundation.Text
import xyz.mcxross.formation.design.tokens.Space
import xyz.mcxross.formation.design.tokens.Tone
import xyz.mcxross.formation.state.recovery.ClaimKeyState
import xyz.mcxross.formation.ui.LocalGraph

@Composable
fun RecoveryScreen(onBack: (() -> Unit)? = null) {
  val graph = LocalGraph.current
  val scope = rememberCoroutineScope()
  val toaster = LocalToaster.current
  val keyState by graph.identity.claims.status.collectAsState()
  val recoveryProblem by graph.recoveryProblem.collectAsState()
  val missing = keyState is ClaimKeyState.Missing
  val protected = (keyState as? ClaimKeyState.Ready)?.protectedOnDevice == true
  var advanced by remember { mutableStateOf(false) }
  var importing by remember(missing, recoveryProblem) { mutableStateOf(missing || recoveryProblem != null) }
  var password by remember { mutableStateOf("") }
  var confirm by remember { mutableStateOf("") }
  var export by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var problem by remember { mutableStateOf<String?>(null) }

  Page(topBar = { TopBar(title = "Reward recovery", onBack = onBack) }) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(Space.gutter),
      verticalArrangement = Arrangement.spacedBy(Space.l)) {
      LiveryRule()
      Text(when { missing -> "Recover your rewards"; protected -> "Protected on this phone"; else -> "Reward protection" }, style = Theme.type.title1)
      Text(when {
        missing -> "Automatic recovery couldn't finish. Your saved rewards are preserved."
        protected -> "Recovery is automatic. No passwords or keys to manage."
        else -> "Your claim key is saved. Automatic protection will retry when the app opens."
      },
        style = Theme.type.body, color = Theme.colors.contentSecondary)
      if (!graph.recovery.available) {
        Notice("Encrypted recovery is available in the Android app. This platform does not have a secure recovery provider yet.", tone = Tone.Warning)
        return@Column
      }
      recoveryProblem?.let { Notice(it, tone = Tone.Warning) }
      problem?.let { Notice(it, tone = Tone.Warning) }
      if (!advanced) {
        Button(if (missing || recoveryProblem != null) "Restore a backup" else "Advanced backup", { advanced = true },
          style = if (missing) ButtonStyle.Primary else ButtonStyle.Secondary)
        Text(if (missing) "Restoring requires an encrypted backup made before the key was lost."
          else "An optional encrypted backup lets you recover after losing this phone or uninstalling the app.",
          style = Theme.type.footnote, color = Theme.colors.contentTertiary)
        NavigationBarSpacer()
        return@Column
      }
      Text(if (importing) "Restore an encrypted backup" else "Create an encrypted backup", style = Theme.type.title3)
      Text(if (importing) "Use a backup and its password to restore the original claim key and reward proofs."
        else "Keep the encrypted backup and its password somewhere safe. It contains your claim key and saved reward proofs.",
        style = Theme.type.footnote, color = Theme.colors.contentSecondary)
      if (importing) TextField(export, { export = it }, label = "Encrypted export", singleLine = false,
        maxLines = 6, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), maxLength = 1_400_000)
      TextField(password, { password = it }, label = "Recovery password", maxLength = 256,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
      if (!importing) TextField(confirm, { confirm = it }, label = "Repeat password", maxLength = 256,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
      Button(if (importing) "Restore rewards" else "Export encrypted recovery", {
        if (busy) return@Button
        problem = null
        if (graph.session.value != null) { problem = "End the active session before restoring or exporting rewards"; return@Button }
        if (!importing && password != confirm) { problem = "The passwords do not match"; return@Button }
        busy = true
        scope.launch {
          try {
            if (importing) {
              graph.recovery.import(export, password)
              graph.recoveryProblem.value = null
              graph.diagnostics.sink(xyz.mcxross.formation.state.diagnostics.TraceSource.RECOVERY)(
                xyz.mcxross.formation.session.DiagnosticEvent(xyz.mcxross.formation.session.DiagnosticCode.RECOVERY_RESTORED))
              export = ""
              graph.ledger.sync()
              graph.resumePendingJoin()
              toaster.show("Rewards restored")
              onBack?.invoke()
            } else graph.platform.external.share(graph.recovery.export(password))
            password = ""
            confirm = ""
          } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e }
            catch (e: Exception) { problem = e.message ?: "Recovery could not finish" }
          finally { busy = false }
        }
      }, loading = busy)
      if (!missing) Button(if (importing) "Create an export" else "Restore an export", {
        importing = !importing; problem = null; password = ""; confirm = ""
      }, style = ButtonStyle.Ghost, enabled = !busy)
      Button("Close advanced backup", { advanced = false; problem = null; password = ""; confirm = ""; export = "" },
        style = ButtonStyle.Ghost, enabled = !busy)
      Text("Recovery preserves entitlement. Each reward still expires at its claim deadline.",
        style = Theme.type.footnote, color = Theme.colors.contentTertiary)
      Spacer(Modifier.height(Space.l))
      NavigationBarSpacer()
    }
  }
}
