# Formation

Formation is a local multiplayer Android app for unlocking shared SKR rewards on Solana. A Seeker hosts a short cooperative challenge. Nearby players join without a wallet, help the group win, and receive a share of the reward.

Play runs over a local network. Funding, unlocks and claims use Solana. Guests can connect a wallet before play or keep their entitlement on the phone and claim later.

Home adapts to the phone. A phone that isn't a Seeker opens on **Nearby**: until a Formation appears there, a panel explains that Seeker owners start Formations and that this phone joins one to play and earn a share. Seeker hardware opens on its games, with a short checklist until it can host: wallet linked and a reward funded. Any phone can host once a wallet holding a Genesis Token is linked; see [Rewards and deployment](#rewards-and-deployment).

Home's **Games** section presents installed games in a horizontal card deck without requiring a wallet or funded reward. Each card shows **Playable** when a linked Seeker has a valid funded reward, **No reward** when funding is missing, **Link to host** on Seeker hardware that hasn't linked a wallet, or **Join a Seeker** on any other phone. Swipe or use the arrows to select a game; brief instructions follow beneath the deck. Cards with an open arrow offer matching funded rewards or interactive practice. Ricochet includes aiming practice and Mosaic includes pinch practice. Guests join through Nearby, QR or code.

The app includes three cooperative games:

- **Overdrive:** each player sees their partner's target symbol and calls it out while rotating their own square to catch a pulse. Both catches complete a shared wave. The group needs 12 successful waves before the clock runs out (36 seconds on Easy down to 25 on Extreme); three failed waves end the attempt.
- **Ricochet:** two phones show halves of one arena. Each player moves an outer paddle to keep a shared pulse in play and aim it at six targets. Alternating catches build speed up to 1.9×; two exchanges charge a return that pierces one target. Deadlines range from 60 seconds on Easy to 40 on Extreme, with three shared misses. An interactive briefing introduces aiming. Touch controls work on emulators without sensors.
- **Mosaic:** 6 or 9 phones each show one piece of the Solana logomark at true physical size. Players lay them in three rows, one per bar, then pinch across every seam to seal it. Every piece has the same physical size: the narrowest usable width and shortest usable height among the phones. Deadlines run from 20 seconds plus 9 per seam on Easy to 20 plus 4 on Extreme, and three wrong pairs end the attempt.

In Overdrive, each successful wave makes both pulses fall 8% faster, so by the final wave they move about two and a half times as fast as the first. A failed wave keeps the pace. The pause between cleared waves shrinks as the group progresses.

Prototype games remain removed. All three games use the same [game API](app/challenges/api/README.md) available to future formats; their rules and stages are isolated from the app's session and reward flow.

Saved claims and reward history remain available. An incomplete result from a removed format cannot resume sealing without that game installed; its saved record is retained.

## Architecture

The Seeker runs the authoritative session. Guests send inputs and render the state it broadcasts. The shared session framework handles admission, readiness, clock synchronization, disconnects and sealing; each challenge module supplies its rules and stage UI. Session logic and Compose UI use Kotlin Multiplatform, with Android integrations for wallets, protected storage and networking.

The application saves completed results separately from settlement. This allows a win to survive a restart and lets chain reconciliation continue without replaying the challenge. The vault enforces reward ownership, the committed roster, payout amounts and one claim per helper slot. It does not verify the gameplay itself.

## How it works

1. **Fund a reward.** A sponsor deposits SKR into the vault for a wallet holding a Seeker Genesis Token. The reward specifies a challenge, player count, split and expiry.
2. **Form a group.** The Seeker advertises a local session over mDNS. Guests can also join through a QR code or a four-letter code while on the same network. A signed handshake proves control of each guest's claim key and checks protocol, format and device compatibility.
3. **Play together.** Phones synchronize their clocks to the Seeker. A round starts after participants are connected, synchronized and ready. The host evaluates the challenge rules and broadcasts the resulting state.
4. **Save and seal the result.** Each phone checks the completed result and signs a commitment binding the reward, session, helper roster and result. Completed results and verified acknowledgements are saved before the app reports them as durable. An interrupted completion can resume sealing after reconnection.
5. **Settle the reward.** The owner approves the unlock through their wallet. The app records signed transactions before submission and tracks each payout batch independently. Wallet-bound helpers are paid to their committed addresses; helpers without a bound wallet claim later using their claim key and Merkle proof.

An unlock and all helper payments may require several transactions. The app distinguishes a recorded unlock from complete settlement and reconciles uncertain submissions against chain state before requesting another signature. A confirmation timeout is not treated as proof of failure.

Each game supplies its rules and stage UI in a separate module. Registration connects it to the existing session and reward flow without game-specific branches in those systems.

## Recovery and connectivity

Completed wins retain their original roster and verified seal acknowledgements. The app can reopen a saved completion, authenticate returning players and collect missing acknowledgements. A real Seeker's pending unlock waits for the owner's action; a pretend Seeker in a `dev` build can retry automatically because it signs with its own claim key.

Each Android installation creates an Ed25519 claim key for session signatures and wallet-free claims. The key is distinct from the user's wallet. Android protects it with Keystore encryption and an independently encrypted local recovery copy. On launch, a usable recovery copy can repair damaged primary storage after its public identity is checked. An unreadable existing key is never silently replaced.

Encrypted key and reward-proof export is available under Profile → Rewards → Reward recovery → Advanced backup. Local protection cannot recover a lost phone, an uninstalled app or both lost protected copies. Those cases require an independent backup. Deferred helper claims expire 30 days after the on-chain unlock; recovery does not extend that deadline.

Without ordinary Wi-Fi, the host can open a local-only hotspot from the lobby. Guests join its Wi-Fi QR code, then join the Formation. This network supports local play but provides no internet access. Solana operations require an internet connection; a saved win can wait until one is available.

Diagnostics stay on the device. The app records a bounded set of session, discovery and settlement events, with explicit export and clear controls in Profile. It does not upload these traces automatically.

## Repository layout

| Path | Responsibility |
| --- | --- |
| `app/design` | Custom Compose tokens, components, effects and icons; no Material dependency |
| `app/core/model` | Opportunities, SKR amounts and reward splits |
| `app/core/crypto` | Ed25519, SHA-2, Base58/64 and the roster Merkle tree |
| `app/core/link` | WebSocket transport, mDNS discovery, beacons and the emulator bridge |
| `app/core/sensors` | Typed availability, shared acquisition, motion processing, haptics and emulator simulation |
| `app/core/session` | Host/client protocol, signed admission, clock synchronization and sealing |
| `app/challenges/api` | Game contracts, validated registration, and focused input/time helpers |
| `app/challenges/overdrive` | Two-player rules, private clues, pacing and custom Compose stage |
| `app/challenges/ricochet` | Shared arena physics, sequenced touch input, and two-screen Compose stage |
| `app/challenges/mosaic` | Logomark layout across phones, size-aware deal, pinch pairing, and a full-screen physical-scale stage |
| `app/solana/vault` | Kotlin vault instructions, account decoding, RPC and SGT lookup |
| `app/shared` | Compose screens, app coordination, settlement, saved completion and recovery |
| `app/androidApp` | Android entry point, Mobile Wallet Adapter, Keystore storage and hotspot lifecycle |
| `app/iosApp` | iOS shell; secure recovery and wallet support do not have Android feature parity |
| `program/formation-vault` | Anchor program holding and distributing locked SKR |
| `scripts` | Emulator linking, local validator setup, devnet provisioning and end-to-end journeys |

## Build and run

The Android build uses Gradle and an installed Android SDK. Anchor and the Solana CLI are needed for program builds and validator testing.

```sh
cd app
./gradlew :androidApp:assembleDevDebug
```

The app has two flavors, one for each path through it:

| Flavor | Who it's for | Seekers |
| --- | --- | --- |
| `dev` | Development and testing. Installs as **Formation Dev** (`xyz.mcxross.formation.dev`) beside the release app. | Any phone can pretend to be a Seeker. Rewards can be simulated, and emulators get simulated motion. |
| `prod` | What people install. | Only a real Seeker hosts, and every phone checks it. Developer tools aren't in the interface. |

Build either flavor as debug or release. `prodDebug` behaves like the store app but stays debuggable, which helps when checking a real Seeker.

The default cluster is devnet, where a verifiable randomness provider is available for [sponsor contests](#sponsor-contests). Override `formation.rpcUrl` and `formation.cluster` when building for another cluster. An Android emulator reaches a validator on the host machine through `10.0.2.2`:

```sh
./gradlew :androidApp:assembleDevDebug \
  -Pformation.rpcUrl=http://10.0.2.2:8899 \
  -Pformation.cluster=localnet
```

### Emulators

From the repository root, install the app on running emulators and forward their session ports:

```sh
scripts/emulators.sh install
scripts/emulators.sh link
```

The installer disables partial frame updates on emulators configured with `skiagl`. If unchanged UI content disappears after animation, stop the AVD and cold-start it with software graphics:

```sh
emulator -avd YOUR_AVD -gpu swiftshader -no-snapshot-load
```

Software graphics preserved the app's frames during the catalogue checks on this development machine; changing the Android UI renderer alone was insufficient. See [Android's graphics acceleration options](https://developer.android.com/studio/run/emulator-acceleration). These settings affect the emulator, not the APK.

On the host, tap **Pretend to be a Seeker** on Home, or turn it on in Profile → Developer. Only the dev flavor has it. A pretend Seeker supplies a test host identity and signs with its own claim key; it can still submit real devnet transactions, and only other dev builds join it. Solana is the default ledger. Turn off **Solana ledger** explicitly for isolated simulation; the prod flavor ignores a saved simulated-ledger preference.

Browse games immediately from Home. Configure explicit development reward fixtures to host: choose a game, select its funded reward and tap **Start Formation**. Join from another emulator's Nearby list. The motion pad supplies debug sensor simulation when a game needs it. Overdrive uses touch; its test driver supplies the partner communication that normally comes from a second person.

### Verification

Run host tests from `app`:

```sh
./gradlew testAndroidHostTest
```

Build and test the vault program from `program`:

```sh
anchor build
cargo test -p formation-vault
```

After changing the program, copy `program/target/idl/formation_vault.json` to `program/formation-vault/idl/`. The Kotlin `IdlContractTest` checks the client against that interface.

Run Overdrive's two-emulator journey on devnet with the Android CLI, a JDK and two running emulators:

```sh
FORMATION_REWARDS="$PWD/scripts/fixtures/overdrive.json" \
  scripts/e2e.py --title Overdrive --code 6 --chain devnet \
  --layout android --driver overdrive
```

The driver reads each phone's visible partner clue and taps the other phone's dial. It exercises the actual game through sealing and confirmed on-chain unlock. The fixture provisions a 120-token devnet reward split equally between the two players when no matching open reward exists. Complete the [devnet setup](#devnet) first. Add `--wallet ADDRESS` to pay the guest's committed share to a devnet recipient; otherwise that share remains reserved for a later claim. Test tokens have no mainnet SKR value. Add `--chain simulated` for an isolated run without transactions.

Ricochet's two-emulator journey uses devnet and its optional autopilot:

```sh
FORMATION_REWARDS="$PWD/scripts/fixtures/ricochet.json" \
  python3 scripts/e2e.py --title Ricochet --code 7 --chain devnet \
  --layout android
```

Devnet is the journey's default chain. It uses an open reward or provisions the explicit fixture through the configured test authority. Complete the [devnet setup](#devnet) first. These are real chain transactions with test tokens, which have no mainnet SKR value. Add `--wallet ADDRESS` to bind the guest payout; otherwise its share remains reserved for a later claim. Add `--chain simulated` for an isolated run without transactions.

Append `--driver manual` to operate Ricochet's paddles yourself. Its debug assistance sends normal inputs and is visibly marked **AUTOPLAY**.

Mosaic's six-emulator journey needs six running emulators; give two of them smaller displays with `adb shell wm size` to vary the shared piece size:

```sh
FORMATION_REWARDS="$PWD/scripts/fixtures/mosaic.json" \
  python3 scripts/e2e.py --title Mosaic --code 8 --players 6 --chain simulated --driver mosaic
```

The Mosaic driver reads each phone's piece and seam strips from its accessibility tree and swipes both phones of every seam toward it at once. Omit `--driver` for debug assistance.

For other registered formats and chain configurations:

```sh
export FORMATION_REWARDS=/absolute/path/rewards.json
scripts/e2e.py --title "My game" --code 6 --chain simulated
scripts/e2e.py --title "My game" --code 6 --chain localnet --wallet GUEST_WALLET
scripts/e2e.py --title "My game" --code 6 --chain devnet --wallet connect --approve
```

The journey hosts a duo, or the `--players` group size with one guest per extra emulator, then joins, plays, seals and unlocks. `--title` and `--code` identify the registered game; there is no default format. Simulated runs seed only the supplied fixtures. Chain runs verify the configured mint, unlocked vault, committed roster, confirmed transaction signatures, and exact token changes for paid shares. They save public verification records under `program/target/e2e`. `--offline` interrupts the host's network at unlock and checks recovery after relaunch.

`--wallet connect` opens the guest's wallet flow. `--approve` taps its connection approval; otherwise the script waits for manual approval. It never enters a wallet password. The default layout reader uses UI Automator. `--layout android` starts the Android CLI's instrumentation server and reuses its bundled protocol and serializer for fast, non-idle reads. Set `ANDROID_CLI_JAR` if its `main.jar` is installed outside `~/.android/cli/bundles`.

Emulator automation verifies rules and the session flow; it does not establish the quality of human play or physical-network reliability.

## Rewards and deployment

### Devnet

Deploy the program with its upgrade authority, then provision a test SKR mint, test SGT group and vault configuration. From the repository root:

```sh
solana program deploy program/target/deploy/formation_vault.so \
  --program-id program/target/deploy/formation_vault-keypair.json \
  -u devnet -k ~/.config/solana/seekers-devnet.json

scripts/devnet.py setup
scripts/devnet.py seeker SEEKER_WALLET
scripts/devnet.py contest --only SEEKER_WALLET --budget 120
```

`scripts/devnet.py contest --help` lists the contests it funds: first come for every Genesis Token (`--budget`, `--budgets`, `--wins`), one token (`--only`), and draws (`--draw BPS --pool SKR`). `scripts/devnet.py draw CONTEST` requests a draw's randomness from ORAO once entry closes, then finalizes it. `scripts/devnet.py settings` shows the vault's settings, and `settings KEY=VALUE` changes them as the admin. The script stores addresses in `program/devnet.json`; `FORMATION_RPC` overrides its endpoint. The program ID is `9NqxUaDuCrk92aDXEvhVppRVW1EmvygXKvtLm6LR5uy7`; keep its deployment keypair and upgrade authority.

### Real Seeker

A Seeker owner links the wallet that holds its Seeker Genesis Token, on the Seeker or on any other phone. The wallet signs one message that proves it holds its key and authorizes a key kept on the phone to host for it. Formation checks the token in the group the vault accepts on its network and rechecks it on every launch. Each joining phone checks that authorization and the reward on chain. The hardware attestation code is kept for a later Seeker present badge.

For devnet rewards, give the linked wallet a test token with `scripts/devnet.py seeker SEED_VAULT_ADDRESS`, then fund a contest it can unlock with `scripts/devnet.py contest --only SEED_VAULT_ADDRESS`. The first command also sends the wallet devnet SOL for fees.

### Local validator

`scripts/localnet.py SEEKER_CLAIM_KEY GUEST_CLAIM_KEY` starts a validator with the program and test state preloaded, then funds the given keys. It funds no contests by default. Set `FORMATION_REWARDS` to fund one contest for SEEKER_CLAIM_KEY's test token per fixture amount, and build the app with the localnet properties above.

### Sponsor contests

A sponsor funds a contest with a pool of SKR. Each Seeker Genesis Token that unlocks takes one budget and plays it with any game whose group fits the budget's guest limit; the host picks the game and group size. The owner gets `W` times each guest's share plus rounding dust, and the total never exceeds the budget, so invented guests only split it more ways.

- **First come.** No registration: each token takes the next budget until the pool runs out. `only` limits a contest to one token, which is how a reward for one Seeker is made. `wins_per_sgt` sets how many budgets one token may take; 1 means strictly once.
- **Draw.** Tokens register before `enter_until`. Anyone then requests ORAO's verifiable randomness, and `finalize_draw` selects `⌈n · bps / 10 000⌉` entries (fewer if the pool can't give each a minimum budget) through a keyed permutation; Home offers **Enter** while a draw is open. A request can be retried after `draw_timeout`, but only while the last one is unanswered.

Entries are keyed by the token's mint and round, so moving a token to another wallet doesn't earn another budget. The wallet holding the token at unlock signs and receives the owner's share. A claim key is paid at most `max_per_key` times per contest. After `play_until` plus the claim window, whatever is left returns to the sponsor.

Every number lives in the vault's settings: the modes offered, a pause switch, owner weight, minimum guest share, minimum pool in budgets, payments per claim key, budgets per token, guest ceiling, claim window, play and entry windows, and draw timeout. Only the admin changes them, within fixed bounds, and each contest keeps a copy from when it was funded.

| Instruction | Who | Enforced behavior |
| --- | --- | --- |
| `init_config` | Upgrade authority | Creates the config with the SKR mint, SGT group, VRF program and settings. |
| `set_settings`, `set_sgt_group`, `set_vrf_program`, `set_admin` | Admin | Changes within bounds; funded contests keep their snapshot. |
| `create_contest` | Sponsor | Deposits the pool, measuring what arrived, and checks the mode, budget, windows and minimum pool. |
| `register` | Token holder | Enters a draw before `enter_until`. |
| `request_draw`, `finalize_draw` | Anyone | Asks ORAO for randomness after entry closes, then reads its answer. |
| `unlock`, `unlock_drawn` | Token holder | Takes a budget while the contest is in play, commits the roster and result, and pays the owner. |
| `claim` | Anyone | Pays a committed guest once: a wallet-bound slot pays only its wallet; an unbound slot needs the claim key's signature. |
| `close_entry`, `close_receipt`, `close_contest` | Anyone | After the claim window, return rent to whoever paid it and what's left to the sponsor. |

Verified on devnet on 5 October 2026:

- A 50% draw over three registered test tokens took ORAO's answer, selected two at 200 SKR each, paid both winners and refused the third with `NotSelected`.
- In the two-emulator Ricochet journey, a pretend Seeker hosted a 120 SKR single-token contest; the unlock settled with the owner paid 90 SKR.
- On 6 October the Overdrive journey, played on Easy, completed all 12 waves and settled the same way: owner paid 90 SKR, the guest's 30 SKR share kept for a later claim.

## Releasing the Android app

[Verify](.github/workflows/verify.yml) runs on every push to `main` and on pull requests: host tests, `prodRelease` lint and both debug flavors; shared iOS tests and the Xcode simulator build; the vault program build and tests; dependency review; and a secret scan.

[Release Android](.github/workflows/release-android.yml) runs when a `v1.2.3` tag on `main` is pushed. It tests, builds a `prodRelease` APK signed with the release key, checks the certificate, version, that it isn't debuggable and that it is the `prod` flavor, attests its provenance and publishes a GitHub Release with `formation-android.apk`, a versioned copy, `SHA256SUMS` and the R8 mapping that turns a release crash's obfuscated stack trace back into source lines (`retrace mapping-1.2.3.txt stacktrace.txt`). Running the workflow by hand rehearses the same build and checks with a throwaway key and uploads the APK as a run artifact, without publishing. The tag sets the version: `v1.2.3` builds versionName `1.2.3` and versionCode `10203`.

One-time setup, in a GitHub environment named `android-release` limited to `v*` tags with a required reviewer:

| Kind | Name | Value |
| --- | --- | --- |
| Secret | `ANDROID_KEYSTORE_B64` | The release keystore, base64-encoded |
| Secret | `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | Its passwords and alias |
| Variable | `ANDROID_CERT_SHA256` | The signing certificate's SHA-256, lowercase hex without colons |
| Variable | `FORMATION_CLUSTER` | `devnet`, `testnet` or `mainnet-beta`; devnet when unset |
| Variable | `FORMATION_RPC_URL` | An `https://` RPC endpoint for that cluster |

Create the keystore once and keep it, with its passwords, outside the repository; losing it means existing installs can't update:

```sh
keytool -genkeypair -keystore formation-release.keystore -alias formation \
  -keyalg RSA -keysize 4096 -validity 10000
keytool -list -v -keystore formation-release.keystore -alias formation | grep SHA256
base64 -i formation-release.keystore | pbcopy
```

Then release from an up-to-date `main`:

```sh
git tag v0.1.0
git push origin v0.1.0
```

Release builds are shrunk and obfuscated by R8; keep rules for code reached only by reflection go in `app/androidApp/proguard-rules.pro`. Locally, `./gradlew :androidApp:assembleProdRelease` in `app` builds an unsigned release. Signed builds take `-PformationKeystoreFile`, `-PformationKeystorePassword`, `-PformationKeyAlias` and `-PformationKeyPassword`, and refuse a non-HTTPS `formation.rpcUrl` or a local cluster.

## Trust boundaries

Joining phones accept a host only with its wallet's authorization and a matching reward on chain, and only take the session state it signs; see [Rewards and deployment](#rewards-and-deployment). Client admission proves control of a claim key, not that each phone belongs to a different person. Local seal verification preserves agreement on a result, but the current vault program does not verify every participant's seal signature or the challenge execution on chain. A modified host can fabricate a roster and result.

The current enforcement is reward ownership, committed payout destinations, the split, one claim per helper slot and refund conditions. Before distributing rewards with material value, evaluate stronger host and participant verification against the intended reward size and abuse model.
