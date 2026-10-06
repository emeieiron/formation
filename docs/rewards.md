# Rewards and the vault

Rewards are held by the `formation-vault` Anchor program (`program/formation-vault`). Its devnet address is `9NqxUaDuCrk92aDXEvhVppRVW1EmvygXKvtLm6LR5uy7`.

## Contests

A sponsor funds a **contest** with a pool of SKR. Each Seeker Genesis Token (SGT) that unlocks takes one **budget** and plays it with any game whose group fits the budget's guest limit. The host picks the game and group size.

- **Split.** The owner gets `W` times each guest's share, plus rounding dust. The total never exceeds the budget, so adding guests only splits it more ways.
- **First come.** No registration: each token takes the next budget until the pool runs out.
  - `only` limits a contest to one token; this is how a reward for a single Seeker is made.
  - `wins_per_sgt` sets how many budgets one token may take; 1 means strictly once.
- **Draw.** Tokens register before `enter_until`. Anyone then requests ORAO's verifiable randomness, and `finalize_draw` selects `⌈n · bps / 10 000⌉` entries (fewer if the pool can't give each a minimum budget). Home offers **Enter** while a draw is open. A request can be retried after `draw_timeout`, but only while the last one is unanswered.
- **One token, one entry.** Entries are keyed by the token's mint and round, so moving a token to another wallet doesn't earn another budget. The wallet holding the token at unlock signs and receives the owner's share.
- **Claims.** A claim key is paid at most `max_per_key` times per contest. After `play_until` plus the claim window, whatever is left returns to the sponsor.

## Settings

All numbers live in the vault's settings: the modes offered, a pause switch, owner weight, minimum guest share, minimum pool in budgets, payments per claim key, budgets per token, guest ceiling, claim window, play and entry windows, and draw timeout. Only the admin changes them, within fixed bounds, and each contest keeps a copy from when it was funded.

## Instructions

| Instruction | Who | What it does |
| --- | --- | --- |
| `init_config` | Upgrade authority | Creates the config with the SKR mint, SGT group, VRF program and settings |
| `set_settings`, `set_sgt_group`, `set_vrf_program`, `set_admin` | Admin | Changes the config within bounds; funded contests keep their copy |
| `create_contest` | Sponsor | Deposits the pool, measuring what arrived, and checks the mode, budget, windows and minimum pool |
| `register` | Token holder | Enters a draw before `enter_until` |
| `request_draw`, `finalize_draw` | Anyone | Asks ORAO for randomness after entry closes, then reads the answer |
| `unlock`, `unlock_drawn` | Token holder | Takes a budget while the contest is open, commits the roster and result, and pays the owner |
| `claim` | Anyone | Pays a committed guest once: a wallet-bound slot pays only its wallet; an unbound slot needs the claim key's signature |
| `close_entry`, `close_receipt`, `close_contest` | Anyone | After the claim window, returns rent to whoever paid it and what's left to the sponsor |

## Devnet

`scripts/devnet.py` signs with `$FORMATION_KEYPAIR` (default `~/.config/solana/seekers-devnet.json`) and keeps addresses in `program/devnet.json`. `FORMATION_RPC` overrides its endpoint.

| Command | What it does |
| --- | --- |
| `setup` | Creates the test SKR mint, the test SGT group and the vault config |
| `seeker WALLET` | Gives `WALLET` a test SGT and SOL for fees |
| `contest [options]` | Funds a contest: first come (`--budget`, `--budgets`, `--wins`), one token (`--only WALLET`), or a draw (`--draw BPS --pool SKR`) |
| `draw CONTEST` | Requests a draw's randomness once entry closes, then finalizes it |
| `settings [KEY=VALUE ...]` | Shows the settings, or changes them as the admin |
| `fund WALLET [SOL]` | Sends devnet SOL |

## Linking a Seeker

A Seeker owner links the wallet that holds their SGT, on the Seeker or on any other phone. The wallet signs one message that authorizes a key kept on the phone to host for it. The app checks the token against the group the vault accepts and rechecks it on every launch.
