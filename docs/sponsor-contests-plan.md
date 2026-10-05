# Sponsor contests

Today a sponsor funds one reward for one Seeker. A contest lets anyone fund a pool of SKR for many Seeker Genesis Token (SGT) owners at once: either every owner who enters, or a random portion of them. The program decides each Seeker's amount from on-chain facts, and owners get more than the guests who play with them. Funding mints a sponsor badge that the app reads to show who is sponsoring.

The aim is that nobody, including the sponsor, the claimants and us, can take more than the rules allow.

Contests know nothing about games. The program sees a Seeker, a roster of guests and an opaque result hash, as `unlock` does today. It doesn't know which game was played, how many players that game takes or what the rules are. Any game registered through the [game API](../app/challenges/api/README.md), now or later, can unlock a contest entry without a program change. Every limit below comes from the pool's money, not from a game.

## Threats

| Who | Attempt | Defence |
| --- | --- | --- |
| Seeker owner | Claim twice by moving the SGT to another wallet | Entries are keyed by **SGT mint**, not wallet: `[b"entry", contest, sgt_mint]` |
| Seeker owner | Claim with many wallets | Each SGT mint gets one entry. Getting an SGT takes a Seeker. |
| Host | Make up guests to keep the guest share | Each entry has a fixed **budget**. Extra guests split it more ways and can't make it bigger, so the most a host can take is one budget. |
| Guest farmer | Run emulators to join many Formations | Guest shares come out of a fixed budget. A claim key can be paid at most 3 times per contest (`max_per_key`). Higher-value contests can add the attestation co-signer (phase 5). |
| Sponsor | Choose who wins in random mode | The random seed comes from a VRF after entry closes, and the sponsor can't change any setting after funding |
| Sponsor | Withdraw after people have entered | The pool stays locked until the play and claim windows end. Closing only returns what's left. |
| Anyone | Run the draw at a slot that suits them | Don't use slot hashes or the clock as randomness. ORAO VRF fixes the request before the oracle answers, so whoever calls `finalize` has no influence. |
| Anyone | Fake a sponsor badge, or pose as a well-known brand | Only the program can mint badges, inside the funding transaction. The app shows a brand only after we approve it (see Branding). |
| Anyone | Flood the app with tiny contests | The program enforces a minimum pool and a minimum budget per Seeker. A contest only gets the app's branding slots once it's approved. |

The vault still can't check the gameplay itself. See [Trust boundaries](../README.md#trust-boundaries). The plan doesn't try to prove each game was played. It limits how much any one SGT can take out.

## Lifecycle

```
create ──► entry window ──► draw ──► play window ──► claim window ──► close
 (badge)   register(SGT)    VRF +    unlock(entry)   claim(helper)    refund rest
                            finalize
```

1. **create_contest**: the sponsor deposits SKR and sets `mode` (`All` or `Portion(bps)`), `enter_until`, `play_until`, and a hash of the branding they submit. The same instruction creates the contest PDA, its vault and the badge mint. Settings can't change after this.
2. **register**: an SGT holder signs and the program checks the SGT using the existing `verify_sgt`. That creates the entry PDA with index `i = contest.entered`. The holder pays the rent and gets it back at close. The app offers **Enter** on Home for each open contest.
3. **draw**: after `enter_until`, anyone can call `request_draw`, which commits a VRF request. `finalize` then reads the revealed seed and records `selected` (k) and `budget` (B). Both instructions are permissionless and can only run once.
4. **unlock(entry, roster_root, roster_size, result)**: the wallet holding the SGT *at unlock time* signs. Entry checks happen here rather than at registration, so an SGT that changes hands still unlocks once. The entry must be selected. This uses the same roster commitment, payout and recovery as today.
5. **claim**: same as the current `claim`, but it pays out of the contest vault and records a receipt per claim key, which enforces `max_per_key`.
6. **close_entry / close_contest**: refund rent once each window ends. Anything left goes back to the sponsor, including unentered or unselected budgets, unclaimed guest shares and rounding dust.

### Fitting into the current program

`Opportunity` already holds the unlock and claim state for one Seeker. Split it into:

- `RewardState`: roster root and size, helper share, claimed bits, result and `unlocked_at`. It's shared.
- `Opportunity`: today's one-Seeker reward, which holds a `RewardState`.
- `Entry`: `contest`, `sgt_mint`, `index`, and a `RewardState`.

Move `unlock` and `claim` into helpers that work on `RewardState`. The opportunity instructions and the new entry instructions become thin wrappers around those helpers, and the current tests continue to cover them.

## How much each participant gets

Everything below is computed on chain from public numbers, so no off-chain service can tilt it.

**Inputs:** pool `P`, entrants `n`, mode, and the values in [Defaults](#defaults): the owner weight `W` and the minimum guest share `f`. The minimum budget per Seeker is `F = f · (W + 1)`, enough to pay one owner and one guest.

**Who's selected (k):**

- `All`: `k = n`. If `P / n < F`, the contest switches to random mode with `k = ⌊P / F⌋`, so nobody receives an amount too small to be worth a transaction fee. The sponsor sees this rule before funding.
- `Portion(bps)`: `k = min(⌈n · bps / 10 000⌉, ⌊P / F⌋)`.

**Which entries are selected:** apply a keyed Feistel permutation over `[0, n)`, seeded with the VRF output. Entry `i` is selected if `perm(i) < k`. This selects exactly `k` entries, and each unlock checks its own entry in constant time, without storing a winner list or a Merkle tree that someone would have to publish.

**Budget:** `B = ⌊P / k⌋`.

**Split within a Formation with `r` guests:**

```
owner = B · W / (W + r)
guest = B / (W + r)      each
```

**How many guests an entry can pay:** `max_guests = min(⌊B / f⌋ − W, guest_ceiling)`. Past that, each guest's share would fall below `f`. `guest_ceiling` is an admin setting, at most 64, the size of the claimed-bits field. No game sets it. `finalize` stores `max_guests` on the contest, and `unlock` refuses a roster with `r < 1` or `r > max_guests`.

The lobby reads `max_guests` and admits at most that many guests, along with the game's own player range from the game API. A game that seats 9 phones can be hosted on any entry with `max_guests ≥ 8`. A bigger pool pays more guests, so it suits bigger groups automatically.

The owner always gets `W` times what each guest gets, so owners get more. The total never goes above `B` however big the roster is, which is why made-up guests can't increase the payout. Rounding dust goes to the owner, as `split` does today.

| Example | B = 300 SKR, W = 3 |
| --- | --- |
| `max_guests` (f = 10) | 27 |
| 1 guest | owner 225, guest 75 |
| 3 guests | owner 150, guests 50 each |
| 9 guests | owner 75, guests 25 each |

## Sponsor badge

`create_contest` creates a Token-2022 mint with these extensions:

- **NonTransferable**: the badge stays with whoever funded the contest.
- **MetadataPointer → itself** plus **TokenMetadata**: `name`, `symbol`, `uri`, and extra fields `contest`, `amount` and `branding_hash`.
- The mint authority is a program PDA. The program mints one badge to the sponsor, then clears the mint authority, so the supply is fixed at 1.

The app confirms a badge is real by checking that its metadata update authority is the program's `badge_authority` PDA, that its `contest` field names a contest account owned by the program, and that the contest's `badge` field names this mint. Each points at the other, so neither can be copied on its own.

Don't put badges in a Token-2022 group. Each new member writes the group's `size`, which would make every sponsor's transaction wait on one account (see Many sponsors at once).

### Branding

The chain proves that a contest was funded. It can't tell whether a logo is offensive or pretending to be someone else. Keep these two questions apart:

- The app developer reviews every brand. Nobody else can approve one.
- The sponsor submits a name, logo, accent color and link to our backend. The backend stores them by content hash, and the hash has to match the contest's `branding_hash`.
- We aim to decide within 24 hours, and the sponsor can only submit while entry is open. On approval, the backend adds it to a **signed manifest**: entries of contest pubkey, branding hash and asset URLs, signed by `manifest_signer`. The app reads that public key from the on-chain settings, so the admin can rotate it without an app release.
- The app shows a brand only when **both** hold: the manifest signature is valid *and* an RPC read confirms that the contest exists, is funded and is inside its play window. The backend can't invent funding, and the chain can't put unreviewed content into the app.
- Until we approve it, the app shows "Sponsored by 7xQ…f9" and the amount.
- To change their branding, the sponsor calls `set_branding_hash` with their badge as proof of authority, which sends it back for review.

Places the app could show a sponsor: a Home hero card for each approved contest, a ribbon on every game card while the Seeker holds a selected entry, a sponsor line in the lobby and briefing, and credits on the win and claim screens.

## Many sponsors at once

### On chain

Solana runs transactions in parallel when they don't write the same accounts, so the design keeps writes from overlapping:

- **No global writable state.** `Config` is read-only during contests. There's no global contest counter and no sponsor list on chain. The indexer finds contests from events and with `getProgramAccounts` memcmp filters.
- **Each contest has its own accounts.** The contest PDA is `[b"contest", sponsor, nonce]` and the badge and vault are derived from it, so two sponsors funding in the same slot never collide. If a funding transaction is retried with the same nonce, `init` fails, which makes retries safe.
- **Contention inside one contest:**
  - `register` writes the `entered` counter. For a popular contest, split the counter across `shards` accounts chosen by `sgt_mint[0] % shards`, with a local index in each. `shards` is an admin setting (default 8, at most 32) that each contest copies when it's created, so changing it never moves an existing contest's PDAs. `finalize` adds the shard sizes, and the global index is `offset[shard] + local`.
  - `unlock` and `claim` write the contest's vault token account. A transfer is a small fraction of the per-account compute limit in each block, so hundreds of payouts from one vault still fit in a single block. Split the vault only if measurements show it's needed.
  - Entries and claim receipts are separate PDAs. One Seeker's unlock never blocks another's.
- **Draws are independent.** Each contest has its own randomness account. A VRF reveal that arrives late delays only that contest. If the reveal hasn't arrived `draw_timeout` after `enter_until`, anyone can call `request_draw` again.

### Indexer and review queue

```
RPC webhook (Helius) / Geyser ──► event queue ──► contest table ──► review queue ──► signed manifest ──► CDN
                                   (dedupe by        (finalized        (FIFO, SLA,       (versioned,
                                    signature)        only)             auto-expire)      short TTL)
```

- Read `ContestCreated`, `Drawn`, `Unlocked` and `Closed` events. Only act on **finalized** commitment. Deduplicate by transaction signature, so replays and webhook retries have no effect.
- The review queue is first in, first out, with a target turnaround. A contest with no decision by `enter_until` still runs, without branding. Only its display is held back, never its funds.
- Republish the manifest each time it changes. Each version has an increasing number, and the app rejects a version lower than one it has already seen, so an old manifest can't be replayed.

### In the app

If more contests are live than there are places to show them:

- **Rotation without paid ranking.** Weight each approved contest by `remaining_pool / remaining_seconds` and rotate the Home hero cards by those weights, with at most one card per sponsor at a time. The weight follows how fast money is being given to players, not how loud a sponsor is.
- **Each player only sees relevant contests.** A Seeker sees the contests it has entered or can still enter. A guest sees the sponsor of the Formation they're in.
- **A Seeker in several contests:** each Formation unlocks one entry. The lobby picks the selected entry that expires first and has a `max_guests` large enough for the group in the lobby, and the owner can switch to another one. The roster commitment names the entry, so a single win can't be counted twice.
- **Settlement queue:** each unlock is tracked separately in the existing settlement tracker, keyed by entry, so a stuck transaction for one contest doesn't hold up the others.

## Phases

1. **Program: split out `RewardState` and move constants into settings.** Move `unlock` and `claim` into shared helpers. Move the reward constants into `Config`, with today's values as defaults and a copy kept on each `Opportunity`. Behaviour doesn't change, and the existing `tests/vault.rs` must still pass. Add tests for a non-admin update, an out-of-range value, and a setting changed after a contest was funded.
2. **Program: contests.** Add `create_contest` with the badge, `register` with counter shards, `request_draw` and `finalize`, `unlock_entry`, `claim_entry` with per-key receipts, and the close instructions. Tests use a mock randomness account, because no ORAO oracle answers on a local validator. Cover these cases:
   - SGT moved between registration and unlock
   - the same SGT entering twice
   - an unselected entry trying to unlock
   - a roster of `max_guests` and one of `max_guests + 1`
   - a budget too small for even one guest
   - a `max_per_key` breach
   - `All` mode switching to random
   - the draw timing out and being requested again
   - two contests created in one slot
   - closing before and after each window
   - a forged badge with the wrong authority, or a contest that points to it without it pointing back
3. **Kotlin vault client.** Add the new instructions and account decoding to `app/solana/vault`, and show contests in Home's Seeker checklist ("Enter").
4. **Indexer, review and manifest.** Add the webhook consumer, review tool, manifest signing and app-side checks. The app should work without branding if the manifest can't be reached.
5. **Optional: attestation co-signer** for contests above a set value. Guests send a key attestation (any genuine Android phone with a locked bootloader) to the backend, which signs a per-roster ticket. `unlock_entry` checks it through the Ed25519 instruction sysvar. This raises the cost of emulator farms in exchange for depending on our service. It's configured per contest, and the sponsor chooses it.
6. **Verification.** Run the draw against ORAO on devnet. Then run a local validator with 2 sponsors and 50 simulated SGTs, then an existing two-phone journey on the `dev` flavor against a contest entry. Repeat it with a second game to show that nothing about the contest depends on which game is played. Record the results in `sponsor-contests-verification.md`.

## Admin settings

Every number in this plan is a setting the admin can change, not a constant in the program. They live in a new `ContestConfig` PDA (`[b"contest_config"]`), which only `Config.admin` can update through `set_contest_config`.

- **Snapshotted per contest.** `create_contest` copies the current settings into the contest account. A change only affects contests created after it, so nobody can change the rules of a pool that's already funded.
- **Bounds the program checks.** `set_contest_config` refuses values outside fixed safety ranges, such as `W` from 1 to 100, `guest_ceiling` from 1 to 64, or windows of zero. A mistake or a stolen admin key can't set a value that breaks the maths or traps funds.
- **One event per change.** Each update emits `ContestConfigChanged` with the old and new values, so the indexer and anyone else can audit it.
- **Make the admin a multisig** (for example a Squads vault) before mainnet. Changes don't touch funded contests, so no timelock is needed.
- **Version and space.** The account has a `version` byte and spare bytes, so later settings can be added without migrating it.

The same treatment applies to today's single-Seeker rewards in phase 1. `CLAIM_WINDOW`, `MIN_PLAYERS`, `MAX_PLAYERS` and `MAX_DIFFICULTY` move into `Config`, with the current values as defaults, and each new `Opportunity` copies them in the same way. `Config` was created with no spare space, so a one-off admin `migrate_config` reallocates it, adds a `version` byte and fills in the defaults.

These stay as constants, because changing them would change account layouts, addresses or proofs:

- The 64-bit claimed field. This is why `guest_ceiling` can't go above 64.
- `MAX_PROOF`, because it follows from the 64-slot roster.
- PDA seeds, the Token-2022 and token-group IDs, and `BPS`.

## Defaults

These are the starting values of the admin settings.

| Setting | Default | Why |
| --- | --- | --- |
| Owner weight `W` | **3** | An owner gets 3 times what each guest gets. With 1 guest that's 75%, against the 50% in today's reward fixtures. With 8 guests the owner still gets 27% and each guest 9%. |
| Minimum guest share `f` | **10 SKR** | The smallest amount anyone should receive. |
| Minimum budget `F` | **`f · (W + 1)` = 40 SKR** | Pays an owner and one guest. Each budget's guest limit then grows with its size: `⌊B / f⌋ − W`. |
| Minimum pool | **`10 · F`** | A contest needs at least 10 budgets. This keeps out spam contests too small to be worth showing. |
| Payments per guest key `max_per_key` | **3 per contest** | A real guest at an event might play with a few different Seekers. Being paid more often than that looks like a farm. Budgets limit the total anyway, so this mainly keeps money moving to different people. |
| Entry window | **3 days** | Long enough for owners to see the contest on Home. |
| Play window | **7 days after the draw** | |
| Claim window | **30 days after unlock** | The same as `CLAIM_WINDOW` today. |
| Draw timeout `draw_timeout` | **1 hour** | ORAO usually answers within seconds, so after an hour anyone can request the draw again. |
| Guest ceiling `guest_ceiling` | **64** | The most the claimed-bits field allows. Lower it to limit how big a group one entry can pay. |
| Registration shards `shards` | **8** | Enough parallel writes for a popular contest's entry window. |
| Randomness program `vrf_program` | **ORAO Classic VRF** | The program checks the randomness account belongs to this program. The address can change, for example between devnet and mainnet deployments, but the CPI interface is fixed. |
| Manifest signer `manifest_signer` | **The app developer's review key** | The app reads it from the chain. Rotating it doesn't need an app release. |
| Leftover money | **Back to the sponsor** | Budgets nobody was selected for, entries that never unlocked, unclaimed guest shares and rounding dust all go back when the contest closes. A second round would need another draw, another window and another review, and would mostly pay Seekers who already played. A sponsor who wants more play can fund a new contest. |
| Branding review | **The app developer, within 24 hours** | Funding never waits for review. A brand that's late or rejected leaves the contest running unbranded, and the sponsor sees the reason for a rejection and can resubmit. |

### Randomness provider

Solana doesn't have an official randomness service. ORAO and MagicBlock both list only mainnet and devnet, not testnet.

Use **ORAO VRF (Classic)**, program `VRFzZoJdhFWL8rkvu87LpKM3RbcVezpMEc6X5GVDr7y`. It's the one that needs the least setup:

- The program ID is the same on devnet and mainnet. You don't register, create a queue or fund an account in advance.
- `request_draw` makes one CPI (`RequestV2`), passing ORAO's network state and treasury. The fee, 0.001 SOL, is paid by whoever calls it.
- It needs no callback. `finalize` reads the request account once ORAO has answered. A late or missing answer only blocks `finalize`, which `draw_timeout` already covers.
- The randomness is read behind one function, so local tests use a mock and nothing else changes.

Formation now runs on **devnet** by default. `scripts/devnet.py` provisions the test SKR mint, the test SGT group and the vault configuration, and records them in `program/devnet.json`.

## Open question

- Whether `All` mode should also allow entering without registration, using the SGT group's on-chain `size` as `n`. That's simpler for owners, but because most owners never claim, the budgets would be tiny.
