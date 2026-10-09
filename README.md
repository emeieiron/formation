![Formation: one Seeker hosts, any phone joins. Pick your challenge: Overdrive, Ricochet or Mosaic. Win together and share the rewards.](docs/images/formation-header.png)

# Formation

Formation turns the phones in a room into one shared game. A Seeker owner starts a Formation, nearby phones join over the local network, and the group plays a short cooperative challenge that only works if everyone does their part. When the group wins, a reward funded on Solana unlocks and is split between the host and every player.

The idea is that a Seeker is a key to a shared experience rather than a solo device: its owner brings people together, and anyone who helps earns a share, with no wallet needed to join.

## Index

- [Watch Formation](#watch-formation)
- [Try Formation](#try-formation)
- [How a Formation works](#how-a-formation-works)
- [Games](#games)
- [Architecture](#architecture)
- [Repository](#repository)
- [Building](#building)
- [Testing](#testing)
- [Trust model](#trust-model)

## Watch Formation

https://github.com/user-attachments/assets/d034e7f3-b810-4a21-ad08-d02b9500eea4

[Download in 4K](https://github.com/emeieiron/formation/releases/download/v0.1.0/formation-v0.1.0-demo.mp4)

## Try Formation

Trying Formation takes no build: the app runs against the vault already deployed on Solana devnet.

1. Download [`formation.apk`](https://github.com/emeieiron/formation/releases/latest/download/formation.apk) on each phone and open it. Android asks once to allow installs from your browser.
2. Put the phones on the same Wi-Fi network. Office and guest networks often block local discovery; the host can open a hotspot from its lobby instead.
3. On the phone that will host, tap **Want to host? Become a test Seeker** on Home, or **Become a test Seeker** in Settings. The phone gets a test Genesis Token and a little devnet SOL for fees.
4. Pick a game and a funded reward, then tap **Start Formation**. The other phones join from Nearby, by QR code or with the four-letter code.

Real Genesis Tokens only exist on mainnet, so on devnet every host, a Seeker owner included, uses a test token. Building from source is for changing Formation; see [Building](#building).

## How a Formation works

1. **A sponsor funds a contest.** SKR is locked in the Formation vault for Seeker Genesis Token holders.
2. **A Seeker owner hosts.** They pick a game and a funded budget. Nearby phones find the Formation on the local network, or join with a QR code or a four-letter code.
3. **The group plays.** The host's phone runs the game; every phone sees its part of it.
4. **Everyone seals the result.** Each phone signs the win, so no one can change the outcome or the list of players afterwards.
5. **The reward unlocks.** The owner approves the unlock in their wallet. Players with a connected wallet are paid immediately; the others keep a proof on the phone and claim later.

## Games

Four games ship today, using the shared session flow through the [game API](app/challenges/api/README.md). Longshot mines ORE on devnet with the picker’s test SOL; the other players make free predictions.

| Game | Players | In short |
| --- | --- | --- |
| [Overdrive](docs/overdrive.md) | 2 | Call out your partner's symbol and rotate your square to catch each pulse |
| [Ricochet](docs/ricochet.md) | 2 | Two phones form one arena; keep a pulse in play and clear the targets |
| [Mosaic](docs/mosaic.md) | 6 or 9 | Lay the phones out to rebuild the Solana mark and pinch every seam closed |
| [Longshot](docs/longshot.md) | 2–8 | One player picks 1–25; everyone else calls WIN or LOSE before ORE reveals its winning tile |

<table>
  <tr>
    <td width="50%"><img src="docs/images/overdrive.png" alt="Two phones mid-game in Overdrive: each shows the symbol to call to the partner, a falling pulse and a square of four symbols"></td>
    <td width="50%"><img src="docs/images/ricochet.png" alt="Two phones side by side forming one Ricochet arena, with a paddle on each outer edge, six red targets and the pulse in flight"></td>
  </tr>
  <tr>
    <td align="center"><sub>Overdrive: wave 10 of 12, six seconds left</sub></td>
    <td align="center"><sub>Ricochet: two phones, one arena</sub></td>
  </tr>
</table>

## Architecture

![Formation responsibilities and trust boundaries: guests verify host authorization, send inputs and seal the roster and result; the host coordinates gameplay; wallets authorize hosting and approve transactions; the Solana vault holds sponsor funds and enforces eligibility, claim proofs and payouts. Phones submit unlocks and later claims through Solana RPC.](docs/images/formation-architecture.png)

- **Session.** The host's phone is authoritative: it admits players, keeps clocks in sync, runs the game rules and broadcasts signed state. Guests send inputs and draw what the host sends. Every phone signs the final result before anything settles.
- **Games.** Each game is a self-contained module with its rules and its screen. The session and reward code have no game-specific branches.
- **Rewards.** The vault program holds contests and pays out. It enforces who can unlock, the committed roster, the split, one payment per guest slot and refunds. It does not check the gameplay itself. See [Rewards and the vault](docs/rewards.md).
- **Hosting.** Only a phone whose linked wallet holds a Seeker Genesis Token can host. On a Seeker that's the Seed Vault Wallet, linked with one approval. On devnet, **Become a test Seeker** gives the phone its own test wallet with a test token instead; everything after linking is the same. Every joining phone checks the wallet's authorization and the reward on chain.
- **Identity and recovery.** Each install has its own claim key, separate from any wallet, protected by the Android Keystore with an encrypted local copy. Wins are saved before they settle, so a result survives a restart or a lost connection. Profile → Rewards → Reward recovery exports an encrypted backup.
- **Offline play.** Without Wi-Fi, the host can open a local hotspot from the lobby. Settlement waits until the host is back online.

### Repository

| Path | What's there |
| --- | --- |
| `app` | Kotlin Multiplatform app, games, shared libraries, and platform integrations |
| `program` | Solana vault program, integration tests, and program interface |
| `faucet` | Cloudflare Worker that gives test Seekers devnet SOL and a test Genesis Token |
| `scripts` | Devnet setup, emulator helpers, end-to-end journeys |
| `docs` | How to play each game, and the reward system |

The app is Kotlin Multiplatform with Compose UI. Android is the main platform; iOS builds and runs the shared code but doesn't yet have wallet support or secure recovery.

## Building

### Prerequisites

- JDK 21 and the Android SDK (compile SDK 37)
- Xcode, for the iOS app
- Rust, the Solana CLI 4.1.2 and Anchor 1.2.0, for the vault program
- Python 3 and [uv](https://docs.astral.sh/uv/), for the scripts

### The app

```sh
cd app
./gradlew :androidApp:assembleDebug
```

There is one app. Debug builds add a motion pad and autoplay in Profile → Developer. The emulator network bridge works in both debug and release builds when running on emulators, so the published build can be tested across two devices with `scripts/emulators.sh link`.

The app talks to devnet and its faucet by default. Pass `-Pformation.rpcUrl=…`, `-Pformation.cluster=…` and `-Pformation.faucetUrl=…` to change them; a mainnet build has no faucet, so **Become a test Seeker** doesn't appear.

### The vault program

```sh
cd program
anchor build
```

After changing the program, copy `program/target/idl/formation_vault.json` to `program/formation-vault/idl/`; the Kotlin client is tested against it.

### Devnet

The vault, a standing test contest and the faucet are already live on devnet, so these steps are only for a fresh deployment. Deploy the program, then create the test SKR mint, a test Genesis Token group owned by the vault, and the vault config:

```sh
solana program deploy program/target/deploy/formation_vault.so \
  --program-id program/target/deploy/formation_vault-keypair.json \
  -u devnet -k ~/.config/solana/seekers-devnet.json
scripts/devnet.py setup
scripts/devnet.py contest --budget 300 --budgets 1000 --wins 3 --hours 2159 --title "Formation test contest"
```

[Rewards and the vault](docs/rewards.md) covers the faucet, the other contest types and the admin commands.

### Development workflow

1. Start two or more Android emulators.
2. `scripts/emulators.sh install` builds the debug app and installs it on every running emulator; `scripts/emulators.sh link` lets them find each other.
3. On the host emulator, tap **Want to host? Become a test Seeker** on Home.
4. Choose a game, pick its funded reward and tap **Start Formation**. Join from another emulator's Nearby list.

If emulator screens stop redrawing after animations, cold-start them with software graphics: `emulator -avd YOUR_AVD -gpu swiftshader -no-snapshot-load`.

## Testing

Run these before pushing; CI runs the same checks on every push and pull request.

```sh
# App: shared and Android tests, release lint, debug build
cd app
./gradlew testAndroidHostTest :androidApp:lintRelease :androidApp:assembleDebug

# iOS: shared tests on the simulator
./gradlew iosSimulatorArm64Test

# Vault program
cd ../program
anchor build
cargo test -p formation-vault

# Scripts
cd ../scripts
python3 -m unittest test_overdrive_driver test_mosaic_driver test_chain_verification test_verify_release_demo
```

### End-to-end journeys

`scripts/e2e.py` drives real emulators through a whole Formation on devnet: the host becomes a test Seeker with the app's own button, hosts a budget from the standing contest, and the group joins, plays, seals and unlocks. It then checks the on-chain result and every payout.

```sh
scripts/e2e.py --title Ricochet --layout android
```

| Option | Use |
| --- | --- |
| `--driver overdrive \| mosaic \| autoplay \| manual` | Who plays: a game-specific driver, the built-in autoplay, or you |
| `--players N` | Group size; one emulator per player |
| `--difficulty Easy \| Normal \| Hard \| Extreme` | For games that offer a choice |
| `--wallet ADDRESS \| connect` | Pay the guest's share to a wallet, or connect one through the wallet app |
| `--offline` | Cut the host's network at unlock and check it recovers |
| `--layout android` | Faster screen reads through the Android CLI |

Records of each run are saved under `program/target/e2e`.

## Trust model

The vault guarantees who can unlock, where every share goes, the split and that each share is paid once. It can't see the game itself, so a modified host app could submit a result that wasn't really played, and a claim key proves a phone, not a person. Weigh this against the size of the rewards before funding contests with real value.
