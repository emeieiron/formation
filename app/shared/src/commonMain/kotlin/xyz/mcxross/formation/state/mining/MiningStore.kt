package xyz.mcxross.formation.state.mining

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import xyz.mcxross.formation.platform.KeyValueStore
import xyz.mcxross.formation.session.FormationJson
import xyz.mcxross.formation.solana.ore.OreDeposit

@Serializable
enum class MiningStatus {
  Prepared,
  Submitted,
  Mining,
  Resolved,
  Settled,
  Failed,
}

@Serializable
data class MiningPosition(
  val reference: String,
  val wallet: String,
  val round: Long,
  val number: Int,
  val lamports: Long,
  val signature: String? = null,
  val validUntil: Long = 0,
  val status: MiningStatus = MiningStatus.Prepared,
  val winningNumber: Int? = null,
  val problem: String? = null,
) {
  fun deposit() = OreDeposit(reference, wallet, round, number, lamports)
}

/** Public receipts only. Signed transactions live in the separate submission journal. */
class MiningStore(private val store: KeyValueStore) {
  var positions: List<MiningPosition> =
    store.get(KEY)?.let {
      FormationJson.decodeFromString(ListSerializer(MiningPosition.serializer()), it)
    } ?: emptyList()
    private set

  fun save(position: MiningPosition) {
    val next = positions.filterNot { it.reference == position.reference } + position
    store.putDurable(
      KEY,
      FormationJson.encodeToString(ListSerializer(MiningPosition.serializer()), next),
    )
    positions = next
  }

  companion object {
    private const val KEY = "ore.devnet.positions.v1"
  }
}
