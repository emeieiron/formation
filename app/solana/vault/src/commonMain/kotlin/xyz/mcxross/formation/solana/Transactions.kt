package xyz.mcxross.formation.solana

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.Message
import com.solana.transaction.Transaction
import com.solana.transaction.TransactionInstruction
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.crypto.Ed25519KeyPair

fun transaction(
  feePayer: SolanaPublicKey,
  blockhash: String,
  vararg instructions: TransactionInstruction,
): Transaction =
  Transaction(
    instructions
      .fold(Message.Builder().addFeePayer(feePayer).setRecentBlockhash(blockhash)) { builder, ix ->
        builder.addInstruction(ix)
      }
      .build()
  )

fun Transaction.signedBy(key: Ed25519KeyPair): Transaction {
  val signer = SolanaPublicKey(key.publicKey)
  val slot = message.accounts.take(message.signatureCount.toInt()).indexOf(signer)
  require(slot >= 0) { "The transaction doesn't need ${signer.base58()} to sign" }
  val signatures = signatures.toMutableList().also { it[slot] = key.sign(message.serialize()) }
  return Transaction(signatures, message)
}

val Transaction.id: String
  get() = Base58.encode(signatures.first())

fun explorerUrl(signature: String, cluster: String): String =
  "https://explorer.solana.com/tx/$signature" +
    when (cluster) {
      "mainnet-beta",
      "mainnet" -> ""
      "localnet" -> "?cluster=custom&customUrl=http%3A%2F%2Flocalhost%3A8899"
      else -> "?cluster=$cluster"
    }
