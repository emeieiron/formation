# Formation vault

The Solana program that holds sponsor-funded SKR rewards and enforces host eligibility, reward unlocks, participant claims, and refunds. Written in Rust with Anchor. See [Rewards and the vault](../docs/rewards.md) for the reward flow and deployment setup.

## Build and test

Requires Rust, Solana CLI **4.1.2**, and Anchor **1.2.0**, as configured in [Anchor.toml](Anchor.toml). Run from this directory:

```sh
anchor build
cargo test -p formation-vault
```

Build first: the LiteSVM integration tests load the compiled program.

After changing the program interface, copy `target/idl/formation_vault.json` into `formation-vault/idl/` to keep the checked-in IDL aligned with the program. The Kotlin client's contract tests use this copy.
