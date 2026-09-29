# Formation

A Seeker owner receives locked SKR ("600 SKR · requires 5 players"). They host a Formation on their Seeker, people nearby join with any phone, and the group plays a short synchronized challenge. When it wins, the Seeker unlocks the reward and it is split between the owner and every helper.

Guests never need a wallet to play: **join → play → help → claim**.

## How it works

- **Local session.** The Seeker runs the session itself: a Ktor WebSocket server found over mDNS, a QR code or a four-letter code. Without Wi-Fi it can open its own local-only hotspot. No backend.
- **One referee.** The Seeker's session is authoritative. Phones sync their clocks to it (NTP-style) and send inputs, and every phone draws the state it sends back. Each challenge format is its own Gradle module.
- **Seal.** On a win, every phone checks the result and signs a seal with its claim key. The claim key is an Ed25519 key the phone creates on first launch. The seal commits to the opportunity, the session, the helpers' Merkle roster root and the result.
- **Unlock and payout.** A guest can connect a wallet in the lobby; its address goes into their roster entry and the seal. The Seeker unlocks the reward on chain and, in the same transaction, pays the owner's share and every connected helper's share straight into their wallets, covering the fees. Helpers who didn't connect claim later with a Merkle proof signed by their claim key.

Formats: **Rally** (keep a spark in the air), **Circuit** (pass a pulse round the group), **Sync** (different moves, one instant), **Formation** (arrange the phones into figures), **Rush** (keep a reactor stable together).

## Layout

| Path | What |
| --- | --- |
| `app/design` | Design system (no Material): tokens, components, effects, icons |
| `app/core/model` | Opportunities, SKR amounts, reward splits |
| `app/core/crypto` | Ed25519, SHA-2, Base58/64, roster Merkle tree |
| `app/core/link` | Local transport: WebSocket links, mDNS, beacons, the emulator bridge |
| `app/core/sensors` | Pose, lean, gestures and haptics, plus a simulator for emulators |
| `app/core/session` | Session host and client, protocol, clock sync, sealing |
| `app/challenges/*` | `api` and one module per format (rules, stage, autopilot) |
| `app/solana/vault` | Kotlin client for the vault program: instructions, accounts, RPC, SGT lookup |
| `app/shared` | App state and screens (Compose Multiplatform) |
| `app/androidApp` | Android entry point: MWA wallet, Keystore secrets, hotspot |
| `program/formation-vault` | Anchor program holding locked SKR |
| `scripts/emulators.sh` | Install on, and connect, running emulators |

## Running

```bash
cd app && ./gradlew :androidApp:assembleDebug
```

The app targets testnet by default. `-Pformation.rpcUrl=…` and `-Pformation.cluster=…` override it; use `http://10.0.2.2:8899` for a validator on the host machine.

### On emulators

Emulators can't see each other's networks. `link` forwards each emulator's session port through the host.

```bash
scripts/emulators.sh install
```

```bash
scripts/emulators.sh link
```

Then on one emulator, go to Settings → Developer → Pretend to be a Seeker (debug builds only, for testing), and host a reward from Home. Other emulators list it under Nearby. The motion pad (the button at the bottom left while playing) holds poses and fakes shakes, swings and covers. Its Autoplay chip lets the phone play its own part, so one person can drive a whole group.

### Tests

```bash
cd app && ./gradlew testAndroidHostTest
```

```bash
cd program && anchor build && cargo test -p formation-vault
```

End to end on two running emulators: the first plays a simulated Seeker, the second a guest. The script installs the app, onboards both phones, hosts a duo, joins it, plays on Autoplay and unlocks. On a chain it checks that the guest's wallet was paid.

```bash
scripts/e2e.py --chain simulated
```

```bash
scripts/e2e.py --chain localnet --wallet GUEST_WALLET
```

```bash
scripts/e2e.py --chain testnet --wallet connect --approve
```

`--offline` cuts the Seeker's network at unlock, kills both apps, restores the network, and checks that the saved win unlocks on its own and the guest's share catches up.

`--wallet connect` goes through the guest's wallet app (Solflare or Phantom). `--approve` taps the wallet's Connect button; without it the script waits for you, and it never types a wallet password. The script reads screens with UI Automator, which allows one client per device, so stop other automation tools first. `--format` picks the challenge (sync by default).

After changing the program, copy `program/target/idl/formation_vault.json` to `program/formation-vault/idl/`. The Kotlin `IdlContractTest` checks the client against it.

## Rewards

Rewards live in the vault program on the configured cluster. Settings → Developer → Solana ledger switches to a **simulated** ledger that keeps everything on the phone (after a restart).

### Testnet

Deploy with the program's upgrade authority, then set up a test SKR mint, a test Seeker Genesis Token group and the vault config. Give each Seeker wallet a test token and some rewards. A simulated Seeker's wallet is its claim key, shown in Settings.

```bash
solana program deploy program/target/deploy/formation_vault.so --program-id program/target/deploy/formation_vault-keypair.json -u testnet -k ~/.config/solana/seekers-testnet.json
```

```bash
scripts/testnet.py setup
```

```bash
scripts/testnet.py seeker SEEKER_WALLET
```

```bash
scripts/testnet.py drops SEEKER_WALLET
```

Addresses are kept in `program/testnet.json`. `FORMATION_RPC` points the script elsewhere, such as a local validator for a rehearsal.

### Without Wi-Fi

In the lobby the Seeker can open a Seeker network: a local-only hotspot with no internet. Guests join it from a Wi-Fi QR code; the join QR code and the Nearby announcement then move to the hotspot. Play needs no internet. The unlock does: once every phone has sealed a win, the Seeker saves it. If it can't reach Solana, the win waits on Home, and a pretend Seeker retries every 30 seconds. Guests keep their share from the moment of the seal, and Rewards picks up the unlock from the chain.

### On a real Seeker

The app recognises a Seeker by its hardware and links it on its own. On first launch it opens the Seed Vault once for approval, then checks the wallet's Seeker Genesis Token on mainnet. Later launches only re-check the stored address, and the approval is remembered for signing unlocks. Other phones never see any of this; they join Formations. To give a real Seeker rewards on testnet, run `scripts/testnet.py seeker SEED_VAULT_ADDRESS` then `drops SEED_VAULT_ADDRESS`. That mints a test Seeker Genesis Token (the vault's testnet config uses a test group) and sends 0.2 testnet SOL for unlock fees.

### Local validator

`scripts/localnet.py SEEKER_CLAIM_KEY GUEST_CLAIM_KEY` starts a validator with the program and its state preloaded, and funds the given keys. Build with `-Pformation.rpcUrl=http://10.0.2.2:8899 -Pformation.cluster=localnet`.

The vault program (`3AzZbKhGFcnaBRRenDDdNSdVumjKoXSkNHsPVeo5q6GW`, keypair in `program/target/deploy`; back it up before deploying):

- `create`: a sponsor locks SKR for the wallet holding a Seeker Genesis Token. The program checks the token on chain, via its Token-2022 group-member entry, against the SGT group in the config (`GT22s89n…99Te` on mainnet).
- `unlock`: only that wallet can call it, before expiry, with a roster of at least `players − 1` helpers. The owner gets their share plus rounding dust.
- `claim`: once per roster slot. A slot bound to a wallet pays only that wallet and anyone can submit it, which is how the Seeker pays connected helpers at unlock. An unbound slot needs the helper's claim key to sign, and pays any wallet. The payer covers the fee and the recipient's token account (about 0.002 SOL).
- `close`: returns unclaimed or expired SKR to the sponsor after 30 days, or earlier once everyone has claimed.

### Trust model

The program enforces who can unlock, the split, one claim per slot and refunds. It can't prove that the roster is made of different people. The challenges are designed so one person can't play several phones at once, but a modified app on the Seeker could invent helpers. Before real rewards, consider per-SGT sponsor limits, device attestation, or checking every helper's seal signature on chain with the Ed25519 precompile.
