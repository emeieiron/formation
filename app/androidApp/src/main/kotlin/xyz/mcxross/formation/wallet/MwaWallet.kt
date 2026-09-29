package xyz.mcxross.formation.wallet

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.platform.SecretStore
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
    return when (val result = adapter.connect(activity).also { remember() }) {
      is TransactionResult.Success -> {
        val auth = result.authResult
        val account = auth.accounts.firstOrNull()
        WalletResult.Ok(
          WalletAccount(Base58.encode(account?.publicKey ?: auth.publicKey), account?.accountLabel)
        )
      }
      is TransactionResult.NoWalletFound -> WalletResult.NoWallet
      is TransactionResult.Failure -> WalletResult.Failed(result.message)
    }
  }

  override suspend fun signAll(transactions: List<ByteArray>): WalletResult<List<ByteArray>> {
    val activity = sender() ?: return WalletResult.Failed("Open Formation to sign")
    return when (
      val result =
        adapter
          .transact(activity) { signTransactions(transactions.toTypedArray()) }
          .also { remember() }
    ) {
      is TransactionResult.Success ->
        result.payload.signedPayloads
          .toList()
          .takeIf { it.size == transactions.size }
          ?.let { WalletResult.Ok(it) } ?: WalletResult.Failed("The wallet didn't sign")
      is TransactionResult.NoWalletFound -> WalletResult.NoWallet
      is TransactionResult.Failure -> WalletResult.Failed(result.message)
    }
  }

  private companion object {
    const val TOKEN = "mwa-auth-token"
  }
}
