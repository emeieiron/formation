# Sponsor contests

A sponsor funds a pool of SKR for Seeker Genesis Token (SGT) owners. Each owner who unlocks gets a fixed budget, played with guests on any game, and the owner earns more than each guest. The program computes every amount from on-chain facts, so nobody, including the sponsor, the claimants and us, can take more than the rules allow.

This replaces today's one-Seeker `Opportunity`. A reward for one Seeker becomes a contest that only that Seeker's token can unlock, so funding, unlocking and claiming have one code path.

## What changed from the first draft

| First draft | Now | Why |
| --- | --- | --- |
| `Opportunity` kept beside contests, sharing a `RewardState` | One model: **Contest** and **Entry** | One unlock path, one client, one set of tests. A single-Seeker reward is a contest limited to one mint. |
| `All` mode: register, then draw | **First come**: no registration, no draw. The first SGTs to unlock each take one budget until the pool runs out. | Most sponsors want "every Seeker who plays". This drops a step for owners, needs no randomness, and avoids the small-budget fallback. |
| `Portion` mode | **Draw**: register, then an ORAO draw selects `k` entries | Only lotteries need randomness, and they keep the Feistel selection. |
| Each reward names a game, a player count and an owner percentage | The contest names none. The host picks any game whose group fits the entry's guest limit. Every split uses the owner weight `W`. | The program can't see which game was played, so a game field couldn't be enforced. |
| The wallet named at funding unlocks | The wallet holding the SGT **at unlock** unlocks, and the owner's share goes there | A reward follows the Seeker, not a wallet it once used. |
| Sponsor badge NFT and branding backend in the first release | Deferred. The contest account itself records the sponsor, pool and branding hash. | The app can show "Sponsored by 7xQ…f9" from the contest account. A badge and reviewed branding can come later. |
| Settings in a new `ContestConfig`, plus `migrate_config` | Settings in `Config`, snapshotted into each contest | It's a fresh deployment, so there's nothing to migrate. |
| Registration counter shards | One counter | Add shards if a real contest shows contention. |

## Accounts

| Account | Seeds | Holds |
| --- | --- | --- |
| `Config` | `[b"config"]` | Admin, SKR mint, SGT group, VRF program and the [settings](#settings) |
| `Contest` | `[b"contest", sponsor, nonce]` | Sponsor, mode, pool, unallocated balance, budget, settings snapshot, windows, draw state, branding hash. Owns the vault token account (an ATA). |
| `Entry` | `[b"entry", contest, sgt_mint, round]` | Index, state, holder at unlock, budget, roster root and size, guest share, claimed bits, result, `unlocked_at`, rent payer |
| `Receipt` | `[b"receipt", contest, claim_key]` | How many shares this claim key has taken from this contest, which enforces `max_per_key` |

Keying entries by SGT mint is what stops one Seeker taking two budgets by moving its token between wallets. `round` runs from 0 to the contest's `wins_per_sgt − 1`, so a sponsor chooses how many budgets one token may take; 1, the default, means strictly once.

## Modes

**First come `{ budget, only, wins_per_sgt }`.** There is no registration and no draw. `unlock(round)` creates the SGT's entry for that round and takes `budget` from the unallocated balance; it fails once less than one budget is left. With `only` set, just that SGT can unlock, which is a single-Seeker reward with `budget = pool`.

**Draw `{ bps }`.**
1. Before `enter_until`, SGT holders `register`, and each gets index `i = entered++`.
2. After `enter_until`, anyone calls `request_draw`. Its ORAO CPI fixes the request, and `finalize_draw` reads the answer. `request_draw` can be retried after `draw_timeout`, but only while the last request is unanswered, so nobody can discard an answer they have seen.
3. `finalize_draw` sets `k = min(⌈n · bps / 10 000⌉, ⌊P / F⌋)` and `budget = ⌊P / k⌋`.
4. Entry `i` is selected if `perm(i) < k`, where `perm` is a keyed Feistel permutation over `[0, n)` seeded by the VRF output.

## Lifecycle

```
create_contest ──► [register ──► request_draw ──► finalize_draw]  (draw only)
      │
      ▼
unlock / unlock_drawn (SGT holder signs) ──► claim (each guest) ──► close_entry / close_receipt ──► close_contest
```

- **create_contest(nonce, amount, mode, budget, only, wins_per_sgt, enter_until, play_until, title, branding)**: deposits the pool, measuring what actually arrived, and snapshots the settings. Nothing changes after this.
- **unlock(roster_root, roster_size, result)**:
  - The signer must hold the entry's SGT now: a Token-2022 account with amount 1, in the config group, owned by the signer.
  - It works only while the contest is in play, before `play_until`.
  - The roster must have between 1 and `max_guests` guests.
  - The owner's share is paid to the signer, and each guest's share is recorded.
- **claim(index, proof)**: the same roster proof as today. It pays from the contest vault and increments the claim key's receipt, which must stay under `max_per_key`.
- **close_entry, close_receipt**: return rent after the claim window.
- **close_contest**: after `play_until + claim_window`, returns everything left to the sponsor: unallocated budgets, unclaimed shares and dust.

## Amounts

**Inputs:** pool `P`, owner weight `W` and minimum guest share `f`. The minimum budget is `F = f · (W + 1)`, enough to pay an owner and one guest.

**Split for `r` guests:** `owner = B · W / (W + r)` plus rounding dust, and `guest = B / (W + r)` each.

**Guest limit:** `max_guests = min(⌊B / f⌋ − W, guest_ceiling)`. First come knows it at creation; a draw sets it at `finalize`. The lobby admits at most that many guests, together with the game's own group sizes.

`create_contest` refuses a budget below `F` and a pool below `min_budgets · F`.

| Example | B = 300 SKR, W = 3 |
| --- | --- |
| `max_guests` (f = 10) | 27 |
| 1 guest | owner 225, guest 75 |
| 3 guests | owner 150, guests 50 each |
| 9 guests | owner 75, guests 25 each |

The total never goes above `B`, so made-up guests split a budget more ways but can't enlarge it.

## Threats

| Who | Attempt | Defence |
| --- | --- | --- |
| Seeker owner | Take two budgets by moving the SGT | Entries are keyed by mint, and `init` fails on a second unlock |
| Seeker owner | Use many wallets | One entry per mint; getting an SGT takes a Seeker |
| Host | Invent guests to keep their shares | The budget is fixed; inventing guests only splits it more ways |
| Guest farmer | Run emulators to join many Formations | A claim key is paid at most `max_per_key` times per contest. An attestation co-signer can follow later. |
| Sponsor | Choose the winners of a draw | The seed comes from ORAO after entry closes, and settings are frozen at funding |
| Sponsor | Withdraw after people play | The vault stays locked until `play_until + claim_window` |
| Anyone | Time the draw to suit them | No slot hashes or clock as randomness. ORAO fixes the request before it answers. |
| Anyone | Spam tiny contests | Minimum budget and minimum pool |

The program still can't check gameplay itself (see [Trust boundaries](../README.md#trust-boundaries)). It limits what any one SGT and any one claim key can take.

## Settings

These live in `Config`. Only the admin can change them, through `set_settings`, within fixed bounds (for example `W` from 1 to 100, `guest_ceiling` from 1 to 64, windows up to a year). Each change emits `SettingsChanged`, and every contest keeps a snapshot from its creation. The admin also sets the SGT group (`set_sgt_group`), the VRF program (`set_vrf_program`) and hands over the role (`set_admin`).

| Setting | Default |
| --- | --- |
| Paused (no new contests) | no |
| Modes offered | first come and draw |
| Owner weight `W` | 3 |
| Minimum guest share `f` | 10 SKR |
| Minimum pool `min_budgets` | 10 budgets |
| Payments per claim key `max_per_key` | 3 |
| Most budgets one SGT may take, `max_wins_per_sgt` | 3 (each contest picks 1 to this) |
| Play window | 1 hour to 90 days |
| Entry window (draw) | 1 hour to 30 days |
| Guest ceiling | 64, the size of the claimed-bits field |
| Claim window | 30 days |
| Draw timeout | 1 hour |
| VRF program | ORAO Classic VRF `VRFzZoJdhFWL8rkvu87LpKM3RbcVezpMEc6X5GVDr7y`, the same on devnet and mainnet |

These stay constants: the 64-bit claimed field, `MAX_PROOF`, PDA seeds, and the Token-2022 and token-group IDs.

## App

- **Home** lists the contests the linked SGT can unlock:
  - first-come contests with a budget left and no entry for this mint;
  - its selected draw entries.

  Open draws offer **Enter**.
- **Hosting** picks one of those, then any game whose group fits `max_guests + 1`. The host proof names the contest and the SGT.
- **Each guest checks on chain** that:
  - the contest exists and is in play;
  - this SGT hasn't unlocked it yet;
  - the wallet holds that SGT;
  - for a draw, the entry is selected.
- **Sponsor shown** as "Sponsored by 7xQ…f9" with the pool, read from the contest account.

## Phases

1. **Program core.**
   - `Config` with settings.
   - `create_contest` in first-come mode, `unlock`, `claim` with receipts, and the close instructions.
   - Tests port today's `tests/vault.rs` cases and add: the same SGT unlocking twice; an SGT moved to another wallet; the pool running out; the guest limit and one over it; `max_per_key`; a single-mint contest; closing before and after each window.
2. **Draw mode.** `register`, `request_draw` and `finalize` against ORAO, with a mock randomness account in tests. Test cases:
   - an unselected entry trying to unlock;
   - the draw timing out and being requested again;
   - a draw with fewer entrants than `k`.
3. **Kotlin client and app.** Account decoding and instructions in `app/solana/vault`. Contest-based rewards, hosting, guest checks and **Enter** in the app. `scripts/devnet.py` funds first-come and single-mint contests.
4. **Devnet.**
   - Deploy under a new program ID and close the old one.
   - Run the two-phone journey against a first-come contest, then with a second game.
   - Run a draw against ORAO.
   - Record the results in `sponsor-contests-verification.md`.
5. **Later:** a sponsor badge (Token-2022, non-transferable), the branding review and signed manifest, registration shards, and an attestation co-signer for high-value contests.
