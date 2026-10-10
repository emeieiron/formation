# Formation ORE worker

A Cloudflare Worker that resolves rounds of Formation's ORE devnet deployment, so [Longshot](../docs/longshot.md) turns finish without anyone running a machine. It does the job of `scripts/ore_devnet.py worker` on Cloudflare.

ORE only starts a round when someone mines. Once a round ends, someone has to finish its entropy and call ORE's `reset` to pick the winning tile and open the next round. The worker watches the board and does exactly that:

1. **Sample.** After the entropy var's end slot, it records the slot hash.
2. **Reveal.** It finds the seed for the current commit in the entropy hash chain and reveals it.
3. **Reset.** It reads the winning tile, finds the miner whose range holds the round's sample when the tile has deposits, and sends `reset`.

Each step sends at most one transaction. The signed transaction is stored before it's sent, so a restart confirms or expires that exact transaction instead of sending another.

## How it runs

One Durable Object, `OreRounds`, runs an alarm loop: every few seconds while a round is live, every 5 seconds while the board is idle, and with backoff up to 30 seconds after errors. A cron trigger every minute restarts the loop if a deploy or eviction dropped its alarm.

The worker signs with its own fee-paying key. ORE's `reset` and Entropy's `sample` and `reveal` accept any signer, so the key holds only the SOL it spends and has no authority over the deployment. Each resolved round costs about 0.0055 devnet SOL, mostly rent for the next round's account.

## Configuration

Deployment settings live in [wrangler.jsonc](wrangler.jsonc).

| Setting | Purpose | Current value |
| --- | --- | --- |
| `ORE_PROGRAM`, `ENTROPY_PROGRAM`, `ORE_MINT_PROGRAM`, `ORE_MINT` | Formation's ORE devnet deployment; the board, config, treasury, entropy var and mint authority are derived from these | [`program/ore-devnet.json`](../program/ore-devnet.json) |
| `FEE_COLLECTOR` | ORE's admin fee collector, which `reset` requires | Deployment admin |
| `MIN_BALANCE_LAMPORTS` | The worker pauses below this balance and resumes once topped up | `10000000` (0.01 SOL) |
| `ROUNDS` | Durable Object binding for the alarm loop | `OreRounds` |

Three secrets are required:

- `RPC_URL`: a devnet RPC endpoint accessible from Cloudflare Workers. The public endpoint refuses Workers, locally too.
- `WORKER_KEY`: the worker's fee-paying keypair as a JSON array of 64 bytes.
- `ENTROPY_ROOT`: the hex root of the entropy hash chain, which is the last entry of `program/target/ore-devnet/entropy-seeds.json`. Anyone holding it can reveal seeds early, so treat it like a key.

Keep them in Wrangler secrets for deployment. For local development, put them in the ignored `.dev.vars` file. Do not commit that file, the keypair or the seeds.

## Local development

Run these commands from this directory with Node.js and npm installed:

```sh
npm ci
npm run check
npm run dev
```

Local runs read and write real devnet state and spend the worker's devnet SOL. Stop any `scripts/ore_devnet.py worker` first, so two workers don't race on the same round.

`npm run dev` enables Wrangler's scheduled-event route. Start the loop with `curl 'http://localhost:8787/__scheduled?cron=*+*+*+*+*'`, then play a Longshot turn or run `scripts/ore_devnet.py smoke --rounds 1 --worker-managed` from the checkout that holds the deployment state.

## Status

`GET` on any path returns the worker's health:

```json
{
  "phase": "idle",
  "error": null,
  "worker": "<WORKER_ADDRESS>",
  "checkedAt": "2026-10-10T06:04:58.757Z",
  "lastResolvedRound": "8",
  "samplesRemaining": "1016"
}
```

| Phase | Meaning |
| --- | --- |
| `idle` | No round is live; the board waits for a first deploy |
| `waiting` | A round is live or in its intermission |
| `r<N>-entropy-sample`, `r<N>-entropy-reveal`, `r<N>-reset` | That transaction was just sent |
| `confirming r<N>-…` | Waiting for a sent transaction to confirm |
| `paused` | The operator needs to act: see `error` |
| `retrying` | A step failed; `error` holds the reason and the loop backs off |

The worker pauses when its balance falls below `MIN_BALANCE_LAMPORTS`, when `RPC_URL` isn't devnet, when the commit isn't in `ENTROPY_ROOT`'s chain, or when fewer than two entropy samples remain. Each played round uses one sample; idle time uses none.

## Deploy and fund

Use a Cloudflare account with Workers and Durable Objects access.

```sh
solana-keygen new -o ore-worker-keypair.json
npx wrangler login
npx wrangler secret put RPC_URL
npx wrangler secret put WORKER_KEY < ore-worker-keypair.json
npx wrangler secret put ENTROPY_ROOT
npm run deploy
```

Fund the worker from the deployment wallet by running this command from the repository root:

```sh
scripts/devnet.py fund WORKER_ADDRESS SOL
```

Delete the local keypair file once the secret is set. Stop any local `scripts/ore_devnet.py worker` before deploying.
