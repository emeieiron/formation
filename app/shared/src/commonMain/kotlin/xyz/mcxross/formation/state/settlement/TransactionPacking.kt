package xyz.mcxross.formation.state.settlement

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.Transaction
import com.solana.transaction.TransactionInstruction
import xyz.mcxross.formation.session.Share
import xyz.mcxross.formation.solana.transaction

data class PayoutBatch(val transaction: Transaction, val shares: List<Share>, val unlock: Boolean)

fun packTransactions(
  payer: SolanaPublicKey,
  blockhash: String,
  unlock: TransactionInstruction?,
  payouts: List<Pair<Share, TransactionInstruction>>,
): List<PayoutBatch> {
  val batches = mutableListOf<Pair<MutableList<TransactionInstruction>, MutableList<Share>>>()
  if (unlock != null) batches += mutableListOf(unlock) to mutableListOf()
  for ((share, ix) in payouts) {
    val last = batches.lastOrNull()
    if (
      last != null &&
        transaction(payer, blockhash, *(last.first + ix).toTypedArray()).serialize().size <= 1_232
    ) {
      last.first += ix
      last.second += share
    } else batches += mutableListOf(ix) to mutableListOf(share)
  }
  return batches.mapIndexed { i, (ixs, shares) ->
    PayoutBatch(
      transaction(payer, blockhash, *ixs.toTypedArray()),
      shares,
      unlock != null && i == 0,
    )
  }
}
