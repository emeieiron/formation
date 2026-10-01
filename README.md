# Formation

Formation is a local multiplayer Android app for unlocking shared SKR rewards on Solana. A Seeker hosts a short cooperative challenge. Nearby players join without a wallet, help the group win, and receive a share of the reward.

Play runs over a local network. Funding, unlocks and claims use Solana. Guests can connect a wallet before play or keep their entitlement on the phone and claim later.

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

Current formats are Rally, Circuit, Sync, Formation and Rush. Their rules and presentation live in separate modules, so changing a challenge does not require replacing the surrounding session and reward flow.

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
| `app/core/sensors` | Motion, pose, gestures, haptics and emulator input simulation |
| `app/core/session` | Host/client protocol, signed admission, clock synchronization and sealing |
| `app/challenges/*` | Shared challenge API and one module per format |
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

In Profile → Developer, enable **Pretend to be a Seeker** on the host. This option is limited to debug builds. Turn off **Solana ledger** and restart the app to use simulated rewards. A debug Seeker and a simulated ledger are separate settings: the former supplies a test host identity; the latter avoids chain transactions.

Host a reward from Home and join it from another emulator's Nearby list. During play, the motion pad simulates sensor inputs. Its Autoplay option lets one tester exercise the group flow.

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

Two-emulator journeys run from the repository root:

```sh
scripts/e2e.py --chain simulated
scripts/e2e.py --chain localnet --wallet GUEST_WALLET
scripts/e2e.py --chain testnet --wallet connect --approve
```

The journey hosts a duo, joins, plays through Autoplay, seals and unlocks. Chain runs can check the guest's token balance. `--format` selects a challenge; Sync is the default. `--offline` interrupts the host's network at unlock and checks recovery after relaunch.

`--wallet connect` opens the guest's wallet flow. `--approve` taps its connection approval; otherwise the script waits for manual approval. It never enters a wallet password. The harness uses UI Automator and may fail to inspect animated screens; the current verification notes describe the layout adapter used for the completed emulator checks.

See [verification results](docs/session-hardening-verification.md) for executed checks and remaining physical-device work. Emulator Autoplay verifies the session flow; it does not establish the quality of human play or physical-network reliability.

## Rewards and deployment

### Testnet

Deploy the program with its upgrade authority, then provision a test SKR mint, test SGT group and vault configuration. From the repository root:

```sh
solana program deploy program/target/deploy/formation_vault.so \
  --program-id program/target/deploy/formation_vault-keypair.json \
  -u testnet -k ~/.config/solana/seekers-testnet.json

scripts/testnet.py setup
scripts/testnet.py seeker SEEKER_WALLET
scripts/testnet.py drops SEEKER_WALLET
```

The provisioning script stores addresses in `program/testnet.json`. `FORMATION_RPC` overrides its endpoint. The program ID is `3AzZbKhGFcnaBRRenDDdNSdVumjKoXSkNHsPVeo5q6GW`; preserve the deployment keypair and upgrade authority when maintaining a deployment.

### Real Seeker

The owner explicitly links a Seeker through the app's wallet flow. Formation checks the wallet's Seeker Genesis Token on mainnet and rechecks the stored address on later launches. Remembered wallet authorization supports subsequent requests; new signing requests still go through the wallet.

For testnet rewards, provision the linked wallet with `scripts/testnet.py seeker SEED_VAULT_ADDRESS`, then `scripts/testnet.py drops SEED_VAULT_ADDRESS`. This creates a token in the test SGT group and funds the wallet with testnet SOL for fees.

### Local validator

`scripts/localnet.py SEEKER_CLAIM_KEY GUEST_CLAIM_KEY` starts a validator with the program and test state preloaded, then funds the given keys. Build the app with the localnet properties above.

### Vault instructions

| Instruction | Enforced behavior |
| --- | --- |
| `create` | A sponsor deposits SKR for a designated Seeker wallet. The program validates SGT membership against the configured Token-2022 group. |
| `unlock` | The designated Seeker wallet commits the roster and result before the reward expires. The owner receives their configured share plus rounding dust. |
| `claim` | Each committed helper slot pays once. A wallet-bound slot pays only its recorded wallet; an unbound slot requires the claim key's signature. The transaction payer covers fees and recipient token-account creation. |
| `close` | Remaining SKR returns to the sponsor after an unopened reward expires, after all helpers claim, or after the 30-day claim window ends. |

## Trust boundaries

Client admission proves control of a claim key, not that each phone belongs to a different person. Local seal verification preserves agreement on a result, but the current vault program does not verify every participant's seal signature or the challenge execution on chain. A modified host can fabricate a roster and result.

The current enforcement is reward ownership, committed payout destinations, the split, one claim per helper slot and refund conditions. Before distributing rewards with material value, evaluate stronger host and participant verification against the intended reward size and abuse model.
