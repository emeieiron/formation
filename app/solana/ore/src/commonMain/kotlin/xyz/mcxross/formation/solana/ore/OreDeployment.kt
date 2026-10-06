package xyz.mcxross.formation.solana.ore

import com.solana.publickey.SolanaPublicKey

data class OreDeployment(
  val rpcUrl: String,
  val genesisHash: String,
  val programId: SolanaPublicKey,
  val entropyProgramId: SolanaPublicKey,
  val mint: SolanaPublicKey,
) {
  companion object {
    val Devnet = OreDeployment(
      rpcUrl = "https://api.devnet.solana.com",
      genesisHash = "EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG",
      programId = SolanaPublicKey.from("5aXjVRQ9CsdCVkhTqjiAfsP5KVG32gtA9RFdrXB4Kjhp"),
      entropyProgramId = SolanaPublicKey.from("5kJgQsJL4KnbQ5Ec1eSjCN1nSbbigAP2YGkekcTHpdt3"),
      mint = SolanaPublicKey.from("ACWuBYysK8DbT5GVCHvaVQ7NWDippahvyfb9JJJ9hhb9"),
    )
  }
}
