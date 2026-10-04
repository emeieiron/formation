# Ricochet escalation and chain demo

This pass increases pressure while retaining the two-paddle controls, teaches aiming before the timed round, and exercises the existing Solana settlement implementation.

## Gameplay

- Upgrade the format to version 3. Increase alternating-return acceleration from 8% to 12%, with a 1.9× speed cap reached after six exchanges. Same-player returns retain speed; a miss resets momentum.
- Serve speeds become 0.72, 0.96, 1.18, and 1.40 logical units/second for Easy through Extreme. Deadlines become 60, 50, 45, and 40 seconds. Paddle sizes and the six-target/three-miss objective remain unchanged.
- Charge the pulse after two alternating exchanges. A charged pulse is visibly marked before impact, passes through one target without bouncing, then returns to ordinary behaviour. Charging and piercing use authoritative events and distinct cues. A miss resets charge as well as momentum.
- Use the same charge and acceleration rules for authoritative physics, bounded presentation prediction, and public-state debug guidance. Clear exactly one target per contact; only the first charged contact pierces.

## Introduction

Add an optional Compose introduction slot to the challenge API. Ricochet uses it in the existing briefing to show a small interactive aiming field. Moving the paddle changes a dotted return trajectory and an animated demonstration pulse. Keep the existing ready/start flow and use only a short instruction. The preview never submits gameplay input or affects scoring.

## Settlement

Production already defaults to `SolanaLedger`. Retain simulation for explicit isolated tests, but use the configured Solana testnet for the documented chain demo unless a different network is selected. Testnet transactions are real chain transactions using test tokens; they do not represent mainnet SKR value.

Inspect the deployed vault/configuration and test funding before making changes. Exercise Ricochet through the chain ledger, confirm the unlock and payouts against RPC state, and record actual signatures and balance changes. Keep signing material outside the repository and logs. Preserve wallet signing and claim-key authorization; never substitute simulated receipts if RPC or funding is unavailable.

## Logical phases

1. Record this scope and acceptance boundaries.
2. Implement stronger pacing, charge rules, format versioning, and focused rule tests.
3. Add the briefing introduction and charge/pierce presentation and sound feedback.
4. Make the real-chain demo explicit and verify chain settlement, then document executed checks and remaining limitations.

## Acceptance

- Six alternating exchanges reach the new cap. Higher difficulties have the declared speeds and deadlines; misses retain cleared targets and reset power.
- Charging requires cooperation. One charged target contact pierces; the following contact uses normal reflection. Delayed ticks and prediction preserve the same trajectory and terminal rules.
- Aiming can be explored in the briefing without a new readiness or network protocol step. Existing games keep their briefing instructions.
- Both emulator halves remain readable and complete through ordinary commands. Chain verification records the network, mint, vault, confirmed transactions, and payout amounts.
- Automated completion establishes reachability. Human pacing, physical Wi-Fi, and speaker/haptic feel remain separate checks.

## Completion

The rules, presentation, introduction slot, release ledger guard, and chain verification are implemented in separate logical commits. The [verification record](ricochet-escalation-verification.md) contains executed checks, emulator observations, exact confirmed testnet payouts, and remaining physical-device work. The [game roadmap](ricochet-plan.md#roadmap) tracks the current follow-up work.
