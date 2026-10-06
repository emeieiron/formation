# Formation

Formation turns the phones in a room into one shared game. A Seeker owner starts a Formation, nearby phones join over the local network, and the group plays a short cooperative challenge that only works if everyone does their part. When the group wins, a reward funded on Solana unlocks and is split between the host and every player.

The idea is that a Seeker is a key to a shared experience rather than a solo device: its owner brings people together, and anyone who helps earns a share, with no wallet needed to join.

## How a Formation works

1. **A sponsor funds a contest.** SKR is locked in the Formation vault for Seeker Genesis Token holders.
2. **A Seeker owner hosts.** They pick a game and a funded budget. Nearby phones find the Formation on the local network, or join with a QR code or a four-letter code.
3. **The group plays.** The host's phone runs the game; every phone sees its part of it.
4. **Everyone seals the result.** Each phone signs the win, so no one can change the outcome or the list of players afterwards.
5. **The reward unlocks.** The owner approves the unlock in their wallet. Players with a connected wallet are paid immediately; the others keep a proof on the phone and claim later.

## Games

Three games ship today. They are a starting set: every game plugs into the same session and reward flow through the [game API](app/challenges/api/README.md), and new ones can be added without changing that flow.

| Game | Players | In short |
| --- | --- | --- |
| [Overdrive](docs/overdrive.md) | 2 | Call out your partner's symbol and rotate your square to catch each pulse |
| [Ricochet](docs/ricochet.md) | 2 | Two phones form one arena; keep a pulse in play and clear the targets |
| [Mosaic](docs/mosaic.md) | 6 or 9 | Lay the phones out to rebuild the Solana mark and pinch every seam closed |

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

```
 Guest phones ──inputs──▶  Host phone (Seeker)  ──unlock, payouts──▶  Formation vault (Solana)
              ◀──state───   runs the session                 ▲
                                                             │
                                          Sponsor ──funds────┘
```

- **Session.** The host's phone is authoritative: it admits players, keeps clocks in sync, runs the game rules and broadcasts signed state. Guests send inputs and draw what the host sends. Every phone signs the final result before anything settles.
- **Games.** Each game is a self-contained module with its rules and its screen. The session and reward code have no game-specific branches.
- **Rewards.** The vault program holds contests and pays out. It enforces who can unlock, the committed roster, the split, one payment per guest slot and refunds. It does not check the gameplay itself. See [Rewards and the vault](docs/rewards.md).
- **Hosting.** Only a phone whose linked wallet holds a Seeker Genesis Token can host. Every joining phone checks the wallet's authorization and the reward on chain.
- **Identity and recovery.** Each install has its own claim key, separate from any wallet, protected by the Android Keystore with an encrypted local copy. Wins are saved before they settle, so a result survives a restart or a lost connection. Profile → Rewards → Reward recovery exports an encrypted backup.
- **Offline play.** Without Wi-Fi, the host can open a local hotspot from the lobby. Settlement waits until the host is back online.

### Repository

| Path | What's there |
| --- | --- |
| `app/androidApp`, `app/iosApp` | Platform entry points: wallets, secure storage, networking |
| `app/shared` | Screens, app coordination, settlement and recovery |
| `app/core` | Session protocol, networking and discovery, crypto, sensors, shared models |
| `app/challenges` | The game API and one module per game |
| `app/design` | Design system: tokens, components, icons |
| `app/solana/vault` | Kotlin client for the vault program |
| `program/formation-vault` | The vault program (Anchor) |
| `scripts` | Devnet and local-validator setup, emulator helpers, end-to-end journeys |
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
./gradlew :androidApp:assembleDevDebug
```

| Flavor | For | Notes |
| --- | --- | --- |
| `dev` | Development | Installs as **Formation Dev** beside the release app. Any phone can pretend to be a Seeker, and emulators get simulated motion. |
| `prod` | Release | Only a linked Seeker wallet can host. No developer tools. |

The app talks to devnet by default. To use another cluster, pass `-Pformation.rpcUrl=… -Pformation.cluster=…`; an emulator reaches a validator on your machine at `http://10.0.2.2:8899`.

### The vault program

```sh
cd program
anchor build
```

After changing the program, copy `program/target/idl/formation_vault.json` to `program/formation-vault/idl/`; the Kotlin client is tested against it.

### Devnet

Deploy the program, then create the test token mint, the test Seeker Genesis Token group and the vault config:

```sh
solana program deploy program/target/deploy/formation_vault.so \
  --program-id program/target/deploy/formation_vault-keypair.json \
  -u devnet -k ~/.config/solana/seekers-devnet.json
scripts/devnet.py setup
```

To host on devnet, give the host's wallet a test Genesis Token and fund a contest for it:

```sh
scripts/devnet.py seeker HOST_WALLET
scripts/devnet.py contest --only HOST_WALLET --budget 120
```

[Rewards and the vault](docs/rewards.md) lists the other contest types and admin commands.

### Development workflow

1. Start two or more Android emulators.
2. `scripts/emulators.sh install` builds the dev app and installs it on every running emulator; `scripts/emulators.sh link` lets them find each other.
3. On the host emulator, tap **Pretend to be a Seeker** on Home. Its claim key acts as the host's wallet: fund a contest for that address with `scripts/devnet.py`.
4. Choose a game, pick its funded reward and tap **Start Formation**. Join from another emulator's Nearby list.

If emulator screens stop redrawing after animations, cold-start them with software graphics: `emulator -avd YOUR_AVD -gpu swiftshader -no-snapshot-load`.

## Testing

Run these before pushing; CI runs the same checks on every push and pull request.

```sh
# App: shared and Android tests, release lint, both debug builds
cd app
./gradlew testAndroidHostTest :androidApp:lintProdRelease :androidApp:assembleDebug

# iOS: shared tests on the simulator
./gradlew iosSimulatorArm64Test

# Vault program
cd ../program
anchor build
cargo test -p formation-vault
```

### End-to-end journeys

`scripts/e2e.py` drives real emulators through a whole Formation: host, join, play, seal and unlock on devnet. It funds a contest for the host, then checks the on-chain result and every payout.

```sh
FORMATION_REWARDS="$PWD/scripts/fixtures/overdrive.json" \
  scripts/e2e.py --title Overdrive --code 6 --driver overdrive --layout android
```

| Option | Use |
| --- | --- |
| `--driver overdrive \| mosaic \| autoplay \| manual` | Who plays: a game-specific driver, the built-in autoplay, or you |
| `--players N` | Group size; one emulator per player |
| `--chain localnet` | Run against `scripts/localnet.py` instead of devnet |
| `--wallet ADDRESS \| connect` | Pay the guest's share to a wallet, or connect one through the wallet app |
| `--offline` | Cut the host's network at unlock and check it recovers |
| `--layout android` | Faster screen reads through the Android CLI |

Records of each run are saved under `program/target/e2e`.

## Trust model

The vault guarantees who can unlock, where every share goes, the split and that each share is paid once. It can't see the game itself, so a modified host app could submit a result that wasn't really played, and a claim key proves a phone, not a person. Weigh this against the size of the rewards before funding contests with real value.
