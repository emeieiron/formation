# ORE devnet client

Kotlin Multiplatform client for Formation's ORE v3.8.25 devnet deployment. The default addresses match `program/ore-devnet.json`; they are separate from ORE mainnet.

The module follows `solana/vault`: `OreProgram` derives addresses and builds instructions, account types decode the pinned Steel layout, and `OreRpc` reads typed accounts using the existing `SolanaRpc` transport. Callers supply the HTTP client and retain control over transaction signing and submission.

## Usage

Add `implementation(projects.solana.ore)` to the consuming module. Supply a Ktor client with the platform's HTTP engine.

```kotlin
val ore = OreRpc(http)
ore.verifyDeployment()
val board = checkNotNull(ore.board())
val instruction = ore.program.deploy(
  signer = wallet,
  authority = wallet,
  roundId = board.roundId,
  amountLamports = 1_000_000uL,
  squares = setOf(16),
)
val unsigned = transaction(wallet, ore.rpc.latestBlockhash(), instruction)
```

Use the existing transaction and wallet utilities to sign `unsigned`, then submit its serialized bytes through `ore.rpc.sendTransaction` and confirm the returned signature. `verifyDeployment` checks the RPC genesis hash and the configured entropy dependency. Instruction builders do not contact the network.

Square indices are **0–24**; claim 17 in the game corresponds to index 16. The deployment amount applies **to each selected square**, rather than being divided among them. The program skips squares that this miner has already deployed on in the round. Combine allocations before submitting and use separate deploy instructions when squares require different amounts.

`checkpoint` processes a completed round for a miner. `claimSol` and `claimOre` build withdrawal instructions; ORE claims accept 1–10,000 basis points and default to the entire claimable balance. On-chain fees determine the amount actually received. The client preserves reward factors as signed 128-bit fixed-point limbs instead of estimating payouts with floating-point arithmetic.

Board, config, treasury, miner, and round reads return `null` for absent accounts. Present accounts must have the expected owner, discriminator, and exact size. Miner authority and round ID must also match the requested address. Round resolution remains the responsibility of the existing devnet worker.

## Verification

From `app`:

```sh
./gradlew :solana:ore:testAndroidHostTest
./gradlew :solana:ore:testAndroidHostTest -PoreDevnetTest=true
```

The default suite runs offline. Its public account snapshots were captured from devnet at slot 508044077, and its instruction references came from the existing solders deployment harness. The opt-in integration test reads the actual deployment through Ktor's OkHttp engine; it does not submit transactions.

To test writes with the external deployment wallet:

```sh
./gradlew :solana:ore:testAndroidHostTest -PoreDevnetWriteTest=true --tests '*OreDevnetWriteTest'
```

The test loads `ORE_DEVNET_PAYER`, or defaults to `~/.config/solana/seekers-devnet.json`. It rejects key files inside the repository and signs in memory using the JVM's Ed25519 provider. It requires the known deployment wallet and an idle board, deploys 0.000625 devnet SOL across all squares, waits for resolution, and verifies checkpoint and claim account changes. The resolver must run separately. Allow 0.015 devnet SOL for the full cycle, including rent for the next round account. This miner auto-returns SOL at checkpoint, so the separate SOL claim transfers zero and still updates its claim timestamp. Public receipts are written to `build/reports/ore-devnet-writes.json`.
