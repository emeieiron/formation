package xyz.mcxross.formation.solana

import com.solana.publickey.SolanaPublicKey

data class VaultSettings(
  val paused: Boolean,
  val modes: Int,
  val ownerWeight: Int,
  val minGuestShare: ULong,
  val minBudgets: Int,
  val maxPerKey: Int,
  val maxWinsPerSgt: Int,
  val guestCeiling: Int,
  val claimWindowSeconds: Long,
  val minPlayWindowSeconds: Long,
  val maxPlayWindowSeconds: Long,
  val minEnterWindowSeconds: Long,
  val maxEnterWindowSeconds: Long,
  val drawTimeoutSeconds: Long,
  val testTokens: Boolean,
) {
  internal companion object {
    fun BorshReader.settings() =
      VaultSettings(
        bool(),
        u8(),
        u16(),
        u64(),
        u16(),
        u8(),
        u8(),
        u8(),
        i64(),
        i64(),
        i64(),
        i64(),
        i64(),
        i64(),
        bool(),
      )
  }
}

data class VaultConfig(
  val admin: SolanaPublicKey,
  val mint: SolanaPublicKey,
  val sgtGroup: SolanaPublicKey,
  val vrfProgram: SolanaPublicKey,
  val settings: VaultSettings,
  val bump: Int,
) {
  companion object {
    internal val DISCRIMINATOR = discriminator(155, 12, 170, 224, 30, 250, 204, 130)

    fun decode(data: ByteArray): VaultConfig =
      with(VaultSettings) {
        with(BorshReader(data, DISCRIMINATOR)) {
          VaultConfig(key(), key(), key(), key(), settings(), u8())
        }
      }
  }
}

enum class ContestMode(val code: Int) {
  FIRST_COME(1),
  DRAW(2),
}

class VaultContest(
  val address: SolanaPublicKey,
  val sponsor: SolanaPublicKey,
  val nonce: ULong,
  val mint: SolanaPublicKey,
  val vault: SolanaPublicKey,
  val sgtGroup: SolanaPublicKey,
  val vrfProgram: SolanaPublicKey,
  val mode: ContestMode,
  val drawBps: Int,
  // Null when any Genesis Token may unlock.
  val only: SolanaPublicKey?,
  val winsPerSgt: Int,
  val pool: ULong,
  val unallocated: ULong,
  val budget: ULong,
  val maxGuests: Int,
  val settings: VaultSettings,
  val createdAtSeconds: Long,
  val enterUntilSeconds: Long,
  val playUntilSeconds: Long,
  val entered: Long,
  val selected: Long,
  val unlocks: Long,
  val drawSeed: ByteArray,
  val drawRequestedAtSeconds: Long,
  val drawAttempts: Int,
  val randomness: ByteArray,
  val drawn: Boolean,
  val title: String?,
  val branding: ByteArray,
  val bump: Int,
) {
  val closesAtSeconds: Long
    get() = playUntilSeconds + settings.claimWindowSeconds

  fun inPlay(nowSeconds: Long): Boolean =
    nowSeconds < playUntilSeconds && (mode == ContestMode.FIRST_COME || drawn)

  // Whether the draw picked the entry registered at [index].
  fun selects(index: Long): Boolean =
    drawn && Selection.permute(randomness, entered, index) < selected

  companion object {
    val DISCRIMINATOR = discriminator(216, 26, 88, 18, 251, 80, 201, 96)

    fun decode(address: SolanaPublicKey, data: ByteArray): VaultContest =
      with(VaultSettings) {
        with(BorshReader(data, DISCRIMINATOR)) {
          VaultContest(
            address = address,
            sponsor = key(),
            nonce = u64(),
            mint = key(),
            vault = key(),
            sgtGroup = key(),
            vrfProgram = key(),
            mode = u8().let { code -> ContestMode.entries.first { it.code == code } },
            drawBps = u16(),
            only = key().takeUnless { it.bytes.all { b -> b == 0.toByte() } },
            winsPerSgt = u8(),
            pool = u64(),
            unallocated = u64(),
            budget = u64(),
            maxGuests = u8(),
            settings = settings(),
            createdAtSeconds = i64(),
            enterUntilSeconds = i64(),
            playUntilSeconds = i64(),
            entered = u32(),
            selected = u32(),
            unlocks = u32(),
            drawSeed = bytes(32),
            drawRequestedAtSeconds = i64(),
            drawAttempts = u8(),
            randomness = bytes(32),
            drawn = bool(),
            title = FormationVault.titleOf(bytes(FormationVault.TITLE_BYTES)),
            branding = bytes(32),
            bump = u8(),
          )
        }
      }
  }
}

enum class EntryState {
  REGISTERED,
  UNLOCKED,
}

class VaultEntry(
  val address: SolanaPublicKey,
  val contest: SolanaPublicKey,
  val sgt: SolanaPublicKey,
  val round: Int,
  val index: Long,
  val payer: SolanaPublicKey,
  val owner: SolanaPublicKey,
  val state: EntryState,
  val budget: ULong,
  val rosterRoot: ByteArray,
  val rosterSize: Int,
  val guestShare: ULong,
  val ownerPaid: ULong,
  val claimed: ULong,
  val result: ByteArray,
  val unlockedAtSeconds: Long,
  val closesAtSeconds: Long,
  val bump: Int,
) {
  fun hasClaimed(index: Int): Boolean = claimed and (1uL shl index) != 0uL

  companion object {
    val DISCRIMINATOR = discriminator(63, 18, 152, 113, 215, 246, 221, 250)

    const val SGT_OFFSET = 40

    fun decode(address: SolanaPublicKey, data: ByteArray): VaultEntry =
      with(BorshReader(data, DISCRIMINATOR)) {
        VaultEntry(
          address = address,
          contest = key(),
          sgt = key(),
          round = u8(),
          index = u32(),
          payer = key(),
          owner = key(),
          state = EntryState.entries[u8()],
          budget = u64(),
          rosterRoot = bytes(32),
          rosterSize = u8(),
          guestShare = u64(),
          ownerPaid = u64(),
          claimed = u64(),
          result = bytes(32),
          unlockedAtSeconds = i64(),
          closesAtSeconds = i64(),
          bump = u8(),
        )
      }
  }
}

enum class VaultError(val code: Int, val message: String) {
  ZERO_AMOUNT(6000, "Amount must be greater than zero"),
  BAD_SETTINGS(6001, "Settings are out of bounds"),
  PAUSED(6002, "New contests are paused"),
  MODE_NOT_ALLOWED(6003, "This kind of contest isn't offered"),
  BAD_MODE(6004, "This contest doesn't work that way"),
  BAD_SHARE(6005, "The share is out of range"),
  BAD_WINS(6006, "This Genesis Token has used all its budgets in this contest"),
  BAD_WINDOW(6007, "The contest's windows are out of range"),
  BAD_BUDGET(6008, "The budget doesn't fit the pool"),
  POOL_TOO_SMALL(6009, "The pool is too small"),
  NOT_A_SEEKER(6010, "This wallet no longer holds the Seeker Genesis Token"),
  NOT_THIS_SEEKER(6011, "This contest is for another Seeker"),
  ENTRY_CLOSED(6012, "Entry has closed"),
  NOT_DRAW_TIME(6013, "The draw hasn't happened yet"),
  NO_ENTRIES(6014, "Nobody entered"),
  ALREADY_DRAWN(6015, "The draw is done"),
  DRAW_PENDING(6016, "The draw is still waiting for randomness"),
  BAD_RANDOMNESS(6017, "That isn't this contest's randomness"),
  NOT_SELECTED(6018, "This Seeker wasn't drawn"),
  ALREADY_UNLOCKED(6019, "This reward has already been unlocked"),
  POOL_SPENT(6020, "Every budget in this contest has been taken"),
  EXPIRED(6021, "This contest has ended"),
  ROSTER_SIZE(6022, "Too many or too few guests for this reward"),
  NOT_UNLOCKED(6023, "The Seeker hasn't unlocked this reward yet"),
  CLAIM_WINDOW_CLOSED(6024, "The time to claim this reward has passed"),
  NOT_IN_ROSTER(6025, "This phone isn't on the reward's roster"),
  ALREADY_CLAIMED(6026, "This share has already been claimed"),
  CLAIM_LIMIT(6027, "This phone has claimed its limit from this contest"),
  PROOF_TOO_LONG(6028, "Proof too long"),
  NOT_CLOSABLE(6029, "Not closable yet"),
  UNAUTHORIZED(6030, "Not allowed"),
  WRONG_MINT(6031, "Wrong token"),
  OVERFLOW(6032, "Arithmetic overflow"),
  TEST_TOKENS_OFF(6033, "Test tokens are off on this network");

  companion object {
    fun of(code: Int): VaultError? = entries.firstOrNull { it.code == code }
  }
}
