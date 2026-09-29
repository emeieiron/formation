package xyz.mcxross.formation.solana

import com.solana.publickey.SolanaPublicKey

data class VaultConfig(
  val admin: SolanaPublicKey,
  val mint: SolanaPublicKey,
  val sgtGroup: SolanaPublicKey,
  val bump: Int,
) {
  companion object {
    internal val DISCRIMINATOR = discriminator(155, 12, 170, 224, 30, 250, 204, 130)

    fun decode(data: ByteArray): VaultConfig =
      with(BorshReader(data, DISCRIMINATOR)) { VaultConfig(key(), key(), key(), u8()) }
  }
}

enum class VaultState {
  OPEN,
  UNLOCKED,
}

class VaultOpportunity(
  val address: SolanaPublicKey,
  val id: ByteArray,
  val sponsor: SolanaPublicKey,
  val seeker: SolanaPublicKey,
  val sgt: SolanaPublicKey,
  val mint: SolanaPublicKey,
  val vault: SolanaPublicKey,
  val amount: ULong,
  val players: Int,
  val ownerBps: Int,
  val challenge: Int,
  val difficulty: Int,
  val title: String?,
  val createdAtSeconds: Long,
  val expiresAtSeconds: Long,
  val state: VaultState,
  val rosterRoot: ByteArray,
  val rosterSize: Int,
  val helperShare: ULong,
  val claimed: ULong,
  val result: ByteArray,
  val unlockedAtSeconds: Long,
  val bump: Int,
) {
  fun hasClaimed(index: Int): Boolean = claimed and (1uL shl index) != 0uL

  companion object {
    internal val DISCRIMINATOR = discriminator(225, 233, 59, 234, 234, 236, 188, 24)

    const val SEEKER_OFFSET = 56
    const val STATE_OFFSET = 246
    const val SIZE = 337

    fun decode(address: SolanaPublicKey, data: ByteArray): VaultOpportunity =
      with(BorshReader(data, DISCRIMINATOR)) {
        VaultOpportunity(
          address = address,
          id = bytes(16),
          sponsor = key(),
          seeker = key(),
          sgt = key(),
          mint = key(),
          vault = key(),
          amount = u64(),
          players = u8(),
          ownerBps = u16(),
          challenge = u16(),
          difficulty = u8(),
          title = FormationVault.titleOf(bytes(FormationVault.TITLE_BYTES)),
          createdAtSeconds = i64(),
          expiresAtSeconds = i64(),
          state = VaultState.entries[u8()],
          rosterRoot = bytes(32),
          rosterSize = u8(),
          helperShare = u64(),
          claimed = u64(),
          result = bytes(32),
          unlockedAtSeconds = i64(),
          bump = u8(),
        )
      }
  }
}

enum class VaultError(val code: Int, val message: String) {
  ZERO_AMOUNT(6000, "Amount must be greater than zero"),
  BAD_PLAYERS(6001, "A Formation takes between 2 and 32 players"),
  BAD_SHARE(6002, "The owner's share must leave something for the helpers"),
  BAD_DIFFICULTY(6003, "Unknown difficulty"),
  ALREADY_EXPIRED(6004, "The opportunity would already be expired"),
  NOT_A_SEEKER(6005, "Not a Seeker Genesis Token held by the Seeker"),
  NOT_OPEN(6006, "This reward has already been unlocked"),
  EXPIRED(6007, "This reward has expired"),
  ROSTER_SIZE(6008, "Not enough helpers for this reward"),
  NOT_UNLOCKED(6009, "The Seeker hasn't unlocked this reward yet"),
  CLAIM_WINDOW_CLOSED(6010, "The time to claim this reward has passed"),
  NOT_IN_ROSTER(6011, "This phone isn't on the reward's roster"),
  ALREADY_CLAIMED(6012, "This share has already been claimed"),
  PROOF_TOO_LONG(6013, "Proof too long"),
  NOT_CLOSABLE(6014, "The opportunity can't be closed yet"),
  UNAUTHORIZED(6015, "Only this reward's Seeker can do that"),
  WRONG_MINT(6016, "Wrong token"),
  OVERFLOW(6017, "Arithmetic overflow");

  companion object {
    fun of(code: Int): VaultError? = entries.firstOrNull { it.code == code }
  }
}
