package xyz.mcxross.formation.solana.ore

import com.solana.publickey.SolanaPublicKey
import io.ktor.client.HttpClient
import xyz.mcxross.formation.solana.SolanaRpc

class OreRpc(val rpc: SolanaRpc, val program: OreProgram = OreProgram()) {
  constructor(http: HttpClient, deployment: OreDeployment = OreDeployment.Devnet) :
    this(SolanaRpc(http, deployment.rpcUrl), OreProgram(deployment))

  suspend fun verifyDeployment(): OreConfig {
    check(rpc.genesisHash() == program.deployment.genesisHash) { "Unexpected Solana cluster" }
    val config = checkNotNull(config()) { "ORE config is not initialized" }
    check(config.protocol.entropyProgramId == program.deployment.entropyProgramId) {
      "Unexpected ORE entropy program"
    }
    check(config.protocol.entropyVar == program.entropyVar()) { "Unexpected ORE entropy account" }
    return config
  }

  suspend fun board(): OreBoard? = read(program.board(), OreBoard::decode)

  suspend fun config(): OreConfig? = read(program.config(), OreConfig::decode)

  suspend fun treasury(): OreTreasury? = read(program.treasury(), OreTreasury::decode)

  suspend fun miner(authority: SolanaPublicKey): OreMiner? =
    read(program.miner(authority), OreMiner::decode)?.also {
      check(it.authority == authority) { "ORE miner authority does not match its address" }
    }

  suspend fun round(id: ULong): OreRound? = read(program.round(id), OreRound::decode)?.also {
    check(it.id == id) { "ORE round ID does not match its address" }
  }

  private suspend fun <T> read(key: SolanaPublicKey, decode: (ByteArray) -> T): T? {
    val account = rpc.account(key) ?: return null
    check(account.owner == program.programId) { "Account ${key.base58()} is not owned by ORE" }
    return decode(account.data)
  }
}
