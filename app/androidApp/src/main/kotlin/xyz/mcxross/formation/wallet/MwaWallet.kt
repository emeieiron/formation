package xyz.mcxross.formation.wallet

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client
import com.solana.mobilewalletadapter.common.ProtocolContract
import java.io.IOException
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.platform.SecretStore
import xyz.mcxross.formation.platform.SignedMessage
import xyz.mcxross.formation.platform.WalletAccount
import xyz.mcxross.formation.platform.WalletPort
import xyz.mcxross.formation.platform.WalletResult

class MwaWallet(
  private val context: Context,
  cluster: String,
  private val secrets: SecretStore,
  private val sender: () -> ActivityResultSender?,
) : WalletPort {
  private val adapter =
    MobileWalletAdapter(
        connectionIdentity =
          ConnectionIdentity(
            identityUri = Uri.parse("https://formation.mcxross.xyz"),
            iconUri = Uri.parse("favicon.ico"),
            identityName = "Formation",
          )
      )
      .apply {
        blockchain =
          when (cluster) {
            "devnet" -> Solana.Devnet
            "testnet" -> Solana.Testnet
            else -> Solana.Mainnet
          }
        // The wallet's approval is remembered across launches, so later signing doesn't ask again.
        authToken = secrets.get(TOKEN)?.decodeToString()?.ifEmpty { null }
      }

  private val requests = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val inFront = MutableStateFlow(true)

  init {
    (context.applicationContext as Application).registerActivityLifecycleCallbacks(
      object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) { inFront.value = true }
        override fun onActivityPaused(activity: Activity) { inFront.value = false }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
      }
    )
  }

  // A wallet can drop a request without answering, for instance when its app is reopened from the launcher, and
  // the adapter then waits forever. A real answer arrives soon after the person is back in Formation, so a longer
  // silence ends the request. The adapter's blocking wait can't be cancelled, so it is left behind.
  private suspend fun <T> answered(request: suspend () -> TransactionResult<T>): TransactionResult<T>? {
    val pending = requests.async { request() }
    return try {
      coroutineScope {
        val abandoned = async {
          inFront.first { !it }
          inFront.first { it }
          delay(ANSWER_GRACE)
        }
        select {
          pending.onAwait { abandoned.cancel(); it }
          abandoned.onAwait { null }
        }
      }
    } finally {
      pending.cancel()
    }
  }

  // The adapter's messages describe its internals, such as a cancelled local association; say instead what
  // happened from where the person stands.
  private fun failed(result: TransactionResult.Failure<*>): WalletResult.Failed {
    val cause = result.e
    val code = (cause as? JsonRpc20Client.JsonRpc20RemoteException)?.code
    return WalletResult.Failed(
      when {
        // A wallet ends the association when its sheet is closed.
        cause is CancellationException || code == ProtocolContract.ERROR_AUTHORIZATION_FAILED ||
          code == ProtocolContract.ERROR_NOT_SIGNED -> DECLINED
        cause is IOException || cause is TimeoutException -> UNREACHABLE
        else -> UNFINISHED
      }
    )
  }

  private fun remember() = secrets.put(TOKEN, (adapter.authToken ?: "").encodeToByteArray())

  override fun installed(): Boolean =
    context.packageManager
      .queryIntentActivities(
        Intent(Intent.ACTION_VIEW, Uri.parse("solana-wallet:/v1/associate/local")),
        0,
      )
      .isNotEmpty()

  override suspend fun connect(): WalletResult<WalletAccount> {
    val activity = sender() ?: return WalletResult.Failed("Open Formation to connect a wallet")
    val result = answered { adapter.connect(activity).also { remember() } } ?: return WalletResult.Failed(NO_ANSWER)
    return when (result) {
      is TransactionResult.Success -> {
        val auth = result.authResult
        val account = auth.accounts.firstOrNull()
        WalletResult.Ok(
          WalletAccount(Base58.encode(account?.publicKey ?: auth.publicKey), account?.accountLabel)
        )
      }
      is TransactionResult.NoWalletFound -> WalletResult.NoWallet
      is TransactionResult.Failure -> failed(result)
    }
  }

  override suspend fun signIn(message: ByteArray): WalletResult<SignedMessage> {
    val activity = sender() ?: return WalletResult.Failed("Open Formation to connect a wallet")
    val result = answered {
      adapter.transact(activity) { auth ->
        val account = auth.accounts.firstOrNull()?.publicKey ?: auth.publicKey
        val signed = signMessagesDetached(arrayOf(message), arrayOf(account)).messages.firstOrNull()
        account to signed?.signatures?.firstOrNull()
      }.also { remember() }
    } ?: return WalletResult.Failed(NO_ANSWER)
    return when (result) {
      is TransactionResult.Success -> {
        val (account, signature) = result.payload
        signature?.let { WalletResult.Ok(SignedMessage(Base58.encode(account), it)) }
          ?: WalletResult.Failed("The wallet didn't sign")
      }
      is TransactionResult.NoWalletFound -> WalletResult.NoWallet
      is TransactionResult.Failure -> failed(result)
    }
  }

  override suspend fun signAll(transactions: List<ByteArray>): WalletResult<List<ByteArray>> {
    val activity = sender() ?: return WalletResult.Failed("Open Formation to sign")
    val result = answered {
      adapter.transact(activity) { signTransactions(transactions.toTypedArray()) }.also { remember() }
    } ?: return WalletResult.Failed(NO_ANSWER)
    return when (result) {
      is TransactionResult.Success ->
        result.payload.signedPayloads
          .toList()
          .takeIf { it.size == transactions.size }
          ?.let { WalletResult.Ok(it) } ?: WalletResult.Failed("The wallet didn't sign")
      is TransactionResult.NoWalletFound -> WalletResult.NoWallet
      is TransactionResult.Failure -> failed(result)
    }
  }

  private companion object {
    const val TOKEN = "mwa-auth-token"
    const val NO_ANSWER = "The wallet closed without answering. Try again."
    const val DECLINED = "Cancelled in the wallet. Try again when you're ready."
    const val UNREACHABLE = "Couldn't reach the wallet app. Try again."
    const val UNFINISHED = "The wallet couldn't finish that. Try again."
    // Past the adapter's own waits after the wallet closes: up to 10 s to connect and 10 s to disconnect.
    val ANSWER_GRACE = 25.seconds
  }
}
