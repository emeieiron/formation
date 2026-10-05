# Formation

Formation is a local multiplayer Android app for unlocking shared SKR rewards on Solana. A Seeker hosts a short cooperative challenge. Nearby players join without a wallet, help the group win, and receive a share of the reward.

Play runs over a local network. Funding, unlocks and claims use Solana. Guests can connect a wallet before play or keep their entitlement on the phone and claim later.

Home's **Games** section presents installed games in a horizontal card deck without requiring a wallet or funded reward. Each card shows **Playable** when a linked host has a valid funded reward, **No reward** when funding is missing, or **Join to play** for guests. Swipe or use the arrows to select a game; brief instructions follow beneath the deck. Cards with an open arrow offer matching funded rewards or interactive practice. Ricochet includes aiming practice and Mosaic includes pinch practice. Guests join through Nearby, QR or code.

The app includes three cooperative games:

- **Overdrive:** each player sees their partner's target symbol and calls it out while rotating their own square to catch a pulse. Both catches complete a shared wave. The group needs 12 successful waves before the clock runs out (36 seconds on Easy down to 25 on Extreme); three failed waves end the attempt.
- **Ricochet:** two phones show halves of one arena. Each player moves an outer paddle to keep a shared pulse in play and aim it at six targets. Alternating catches build speed up to 1.9×; two exchanges charge a return that pierces one target. Deadlines range from 60 seconds on Easy to 40 on Extreme, with three shared misses. An interactive briefing introduces aiming. Touch controls work on emulators without sensors. See [the implementation and demo](docs/ricochet.md).
- **Mosaic:** 6 or 9 phones each show one piece of the Solana logomark at true physical size. Players lay them in three rows, one per bar, then pinch across every seam to seal it. Every piece has the same physical size: the narrowest usable width and shortest usable height among the phones. Deadlines run from 20 seconds plus 9 per seam on Easy to 20 plus 4 on Extreme, and three wrong pairs end the attempt. See [the implementation and demo](docs/mosaic.md).

In Overdrive, each successful wave makes both pulses fall 8% faster, so by the final wave they move about two and a half times as fast as the first. A failed wave keeps the pace. The pause between cleared waves shrinks as the group progresses.

Prototype games remain removed. All three games use the same [game API](app/challenges/api/README.md) available to future formats; their rules and stages are isolated from the app's session and reward flow.

Saved claims and reward history remain available. An incomplete result from a removed format cannot resume sealing without that game installed; its saved record is retained.

## Architecture

![Formation architecture: guest phones exchange inputs, seals, state and clock samples with the Seeker over a local network. The Seeker submits recoverable unlock and payout transactions to the Solana vault. Guests retain a proof and claim key for deferred claims. Sponsors fund the vault, which pays the owner and helpers.](docs/images/architecture.png)

[Vector diagram](docs/images/architecture.svg)

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

Completed wins retain their original roster and verified seal acknowledgements. The app can reopen a saved completion, authenticate returning players and collect missing acknowledgements. A real Seeker's pending unlock waits for the owner's action; a debug Seeker can retry automatically because it signs with its own claim key.

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
| `scripts` | Emulator linking, local validator setup, testnet provisioning and end-to-end journeys |

## Build and run

The Android build uses Gradle and an installed Android SDK. Anchor and the Solana CLI are needed for program builds and validator testing.

```sh
cd app
./gradlew :androidApp:assembleDebug
```

The default cluster is testnet. Override `formation.rpcUrl` and `formation.cluster` when building for another cluster. An Android emulator reaches a validator on the host machine through `10.0.2.2`:

```sh
./gradlew :androidApp:assembleDebug \
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

In Profile → Developer, enable **Pretend to be a Seeker** on the host. This option is limited to debug builds. A debug Seeker supplies a test host identity and signs with its own claim key; it can still submit real testnet transactions. Solana is the default ledger. Turn off **Solana ledger** explicitly for isolated simulation; release builds ignore a saved simulated-ledger preference.

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

Run Overdrive's two-emulator journey on testnet with the Android CLI, a JDK and two running emulators:

```sh
FORMATION_REWARDS="$PWD/scripts/fixtures/overdrive.json" \
  scripts/e2e.py --title Overdrive --code 6 --chain testnet \
  --layout android --driver overdrive
```

The driver reads each phone's visible partner clue and taps the other phone's dial. It exercises the actual game through sealing and confirmed on-chain unlock. The fixture provisions a 120-token testnet reward split equally between the two players when no matching open reward exists. Complete the [testnet setup](#testnet) first. Add `--wallet ADDRESS` to pay the guest's committed share to a testnet recipient; otherwise that share remains reserved for a later claim. Test tokens have no mainnet SKR value. Add `--chain simulated` for an isolated run without transactions.

Ricochet's two-emulator journey uses testnet and its optional autopilot:

```sh
FORMATION_REWARDS="$PWD/scripts/fixtures/ricochet.json" \
  python3 scripts/e2e.py --title Ricochet --code 7 --chain testnet \
  --layout android
```

Testnet is the journey's default chain. It uses an open reward or provisions the explicit fixture through the configured test authority. Complete the [testnet setup](#testnet) first. These are real chain transactions with test tokens, which have no mainnet SKR value. Add `--wallet ADDRESS` to bind the guest payout; otherwise its share remains reserved for a later claim. Add `--chain simulated` for an isolated run without transactions.

Append `--driver manual` to operate Ricochet's paddles yourself. Its debug assistance sends normal inputs and is visibly marked **AUTOPLAY**. The [format 3 verification](docs/ricochet-escalation-verification.md) records the aiming introduction, charged pulse, compact display, and confirmed testnet payouts.

Mosaic's six-emulator journey needs six running emulators; give two of them smaller displays with `adb shell wm size` to vary the shared piece size:

```sh
FORMATION_REWARDS="$PWD/scripts/fixtures/mosaic.json" \
  python3 scripts/e2e.py --title Mosaic --code 8 --players 6 --chain simulated --driver mosaic
```

The Mosaic driver reads each phone's piece and seam strips from its accessibility tree and swipes both phones of every seam toward it at once. Omit `--driver` for debug assistance. The [verification record](docs/mosaic-verification.md) lists the executed checks and what still needs physical phones.

For other registered formats and chain configurations:

```sh
export FORMATION_REWARDS=/absolute/path/rewards.json
scripts/e2e.py --title "My game" --code 6 --chain simulated
scripts/e2e.py --title "My game" --code 6 --chain localnet --wallet GUEST_WALLET
scripts/e2e.py --title "My game" --code 6 --chain testnet --wallet connect --approve
```

The journey hosts a duo, or the `--players` group size with one guest per extra emulator, then joins, plays, seals and unlocks. `--title` and `--code` identify the registered game; there is no default format. Simulated runs seed only the supplied fixtures. Chain runs verify the configured mint, unlocked vault, committed roster, confirmed transaction signatures, and exact token changes for paid shares. They save public verification records under `program/target/e2e`. `--offline` interrupts the host's network at unlock and checks recovery after relaunch.

`--wallet connect` opens the guest's wallet flow. `--approve` taps its connection approval; otherwise the script waits for manual approval. It never enters a wallet password. The default layout reader uses UI Automator. `--layout android` starts the Android CLI's instrumentation server and reuses its bundled protocol and serializer for fast, non-idle reads. Set `ANDROID_CLI_JAR` if its `main.jar` is installed outside `~/.android/cli/bundles`.

See the [documentation index](docs/README.md), [session verification](docs/session-hardening-verification.md), [Overdrive verification](docs/overdrive-verification.md), [Ricochet's plan and roadmap](docs/ricochet-plan.md), and [Mosaic verification](docs/mosaic-verification.md). Emulator automation verifies rules and the session flow; it does not establish the quality of human play or physical-network reliability.

## Rewards and deployment

### Testnet

Deploy the program with its upgrade authority, then provision a test SKR mint, test SGT group and vault configuration. From the repository root:

```sh
solana program deploy program/target/deploy/formation_vault.so \
  --program-id program/target/deploy/formation_vault-keypair.json \
  -u testnet -k ~/.config/solana/seekers-testnet.json

scripts/testnet.py setup
scripts/testnet.py seeker SEEKER_WALLET
FORMATION_REWARDS=/absolute/path/rewards.json scripts/testnet.py drops SEEKER_WALLET
```

The provisioning script stores addresses in `program/testnet.json`. `FORMATION_RPC` overrides its endpoint. The program ID is `3AzZbKhGFcnaBRRenDDdNSdVumjKoXSkNHsPVeo5q6GW`; preserve the deployment keypair and upgrade authority when maintaining a deployment.

### Real Seeker

The owner explicitly links a Seeker through the app's wallet flow. The wallet signs a one-time message to prove it holds the key for its address, then Formation checks that address's Seeker Genesis Token on mainnet and rechecks it on later launches. Remembered wallet authorization supports subsequent requests; new signing requests still go through the wallet.

A Genesis Token shows who owns a Seeker, not that the phone in hand is one. Hosting and practice also need the phone's secure hardware to attest that it is a Seeker with a locked bootloader running Formation, and every joining phone checks that attestation itself. [Seeker presence](docs/seeker-presence.md) describes the proof; Settings → Seeker → **Check** runs it on the phone.

For testnet rewards, provision the linked wallet with `scripts/testnet.py seeker SEED_VAULT_ADDRESS`, then `FORMATION_REWARDS=/absolute/path/rewards.json scripts/testnet.py drops SEED_VAULT_ADDRESS`. This creates a token in the test SGT group and funds the wallet with testnet SOL for fees.

### Local validator

`scripts/localnet.py SEEKER_CLAIM_KEY GUEST_CLAIM_KEY` starts a validator with the program and test state preloaded, then funds the given keys. It creates no game rewards by default. Set `FORMATION_REWARDS` to seed rewards for registered formats, and build the app with the localnet properties above.

### Vault instructions

| Instruction | Enforced behavior |
| --- | --- |
| `create` | A sponsor deposits SKR for a designated Seeker wallet. The program validates SGT membership against the configured Token-2022 group. |
| `unlock` | The designated Seeker wallet commits the roster and result before the reward expires. The owner receives their configured share plus rounding dust. |
| `claim` | Each committed helper slot pays once. A wallet-bound slot pays only its recorded wallet; an unbound slot requires the claim key's signature. The transaction payer covers fees and recipient token-account creation. |
| `close` | Remaining SKR returns to the sponsor after an unopened reward expires, after all helpers claim, or after the 30-day claim window ends. |

## Releasing the Android app

[Verify](.github/workflows/verify.yml) runs on every push to `main` and on pull requests: host tests, release lint and a debug build; shared iOS tests and the Xcode simulator build; the vault program build and tests; dependency review; and a secret scan.

[Release Android](.github/workflows/release-android.yml) runs when a `v1.2.3` tag on `main` is pushed. It tests, builds an APK signed with the release key, checks the certificate, version and that it isn't debuggable, attests its provenance and publishes a GitHub Release with `formation-android.apk`, a versioned copy, `SHA256SUMS` and the R8 mapping that turns a release crash's obfuscated stack trace back into source lines (`retrace mapping-1.2.3.txt stacktrace.txt`). Running the workflow by hand rehearses the same build and checks with a throwaway key and uploads the APK as a run artifact, without publishing. The tag sets the version: `v1.2.3` builds versionName `1.2.3` and versionCode `10203`.

One-time setup, in a GitHub environment named `android-release` limited to `v*` tags with a required reviewer:

| Kind | Name | Value |
| --- | --- | --- |
| Secret | `ANDROID_KEYSTORE_B64` | The release keystore, base64-encoded |
| Secret | `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | Its passwords and alias |
| Variable | `ANDROID_CERT_SHA256` | The signing certificate's SHA-256, lowercase hex without colons |
| Variable | `FORMATION_CLUSTER` | `testnet`, `devnet` or `mainnet-beta` |
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

Release builds are shrunk and obfuscated by R8; keep rules for code reached only by reflection go in `app/androidApp/proguard-rules.pro`. Locally, `./gradlew :androidApp:assembleRelease` in `app` builds an unsigned release. Signed builds take `-PformationKeystoreFile`, `-PformationKeystorePassword`, `-PformationKeyAlias` and `-PformationKeyPassword`, and refuse a non-HTTPS `formation.rpcUrl` or a local cluster.

## Trust boundaries

Joining phones accept a host only with a hardware attestation of a Seeker, and only take the session state it signs; see [Seeker presence](docs/seeker-presence.md). Client admission proves control of a claim key, not that each phone belongs to a different person. Local seal verification preserves agreement on a result, but the current vault program does not verify every participant's seal signature or the challenge execution on chain. A modified host can fabricate a roster and result.

The current enforcement is reward ownership, committed payout destinations, the split, one claim per helper slot and refund conditions. Before distributing rewards with material value, evaluate stronger host and participant verification against the intended reward size and abuse model.
