# Formation faucet

A Cloudflare Worker that funds test wallets on Solana devnet and mints test Genesis Tokens. The app calls it when a player selects **Become a test Seeker**, allowing them to host without funding a wallet manually.

The Worker transfers SOL from its own funded account and pays the transaction fees. It does not request a Solana airdrop. The Formation vault must already be initialized with test tokens enabled on the configured network. See [Rewards and the vault](../docs/rewards.md) for the on-chain setup.

## Configuration

Deployment settings live in [wrangler.jsonc](wrangler.jsonc).

| Setting | Purpose | Current value |
| --- | --- | --- |
| `PROGRAM_ID` | Formation vault program | Configured devnet deployment |
| `GRANT_LAMPORTS` | SOL transfer per eligible wallet request | `100000000` (0.1 SOL) |
| `REQUESTS_PER_IP_PER_DAY` | Request limit per IP and UTC calendar day | `10` |
| `LIMITS` | Cloudflare KV binding for request counts and wallet grants | Configured namespace ID |

Two secrets are required:

- `RPC_URL`: a devnet RPC endpoint accessible from Cloudflare Workers.
- `FAUCET_KEY`: the funded devnet account's secret key as a JSON array of 64 bytes.

Keep both in Wrangler secrets for deployment. For local development, put them in the ignored `.dev.vars` file. Do not commit that file or the account's keypair.

## Local development

Run these commands from this directory with Node.js and npm installed:

```sh
npm ci
npm run check
npm run dev
```

Set `RPC_URL` and `FAUCET_KEY` in `.dev.vars` before making a request. Local Worker execution still submits real transactions to the configured devnet endpoint and spends the faucet's devnet balance.

`npm run check` runs TypeScript checking without emitting files. `npm run dev` starts Wrangler's development server; use the URL printed by Wrangler when testing the API.

## API

Send a JSON POST request containing the recipient's base58 wallet address:

```sh
curl -X POST http://localhost:8787 \
  -H 'Content-Type: application/json' \
  --data '{"wallet":"<BASE58_WALLET_ADDRESS>"}'
```

The handler is not restricted to a particular URL path. It checks the IP request count, adds a SOL transfer if the wallet has no recorded grant for the current UTC day, and adds a mint instruction if the wallet's derived test-token mint account does not exist. Both instructions, when needed, are sent in one transaction.

A successful response contains:

```json
{
  "mint": "<TEST_TOKEN_MINT_ADDRESS>",
  "signature": "<TRANSACTION_SIGNATURE>"
}
```

If neither funding nor minting is needed, the response is still `200`, with `signature: null`.

| Status | Meaning |
| --- | --- |
| `200` | Transaction confirmed or no transaction needed |
| `400` | Invalid JSON or wallet address |
| `405` | Method other than POST |
| `429` | IP request limit reached |
| `502` | Transaction failure or an error while processing the request |
| `503` | Vault configuration account missing when minting is needed |
| `504` | Transaction submitted but confirmation polling timed out |

Errors contain an `error` message and may include a transaction `signature`. A confirmation timeout does not prove the transaction failed; check the returned signature before deciding whether to retry.

## Deploy and fund

Use a Cloudflare account with Workers and KV access. For a separate deployment, create a KV namespace and update the `LIMITS` namespace ID in `wrangler.jsonc` to match your account.

```sh
npx wrangler login
npx wrangler secret put RPC_URL
npx wrangler secret put FAUCET_KEY
npm run deploy
```

Fund the faucet account from the deployment wallet by running this command from the repository root:

```sh
scripts/devnet.py fund FAUCET_ADDRESS SOL
```

Replace `FAUCET_ADDRESS` with the faucet account's public address and `SOL` with the amount to transfer. The account needs enough devnet SOL for grants, token-account creation, and transaction fees.

## Operational limits

The daily counters use UTC calendar days, rather than rolling 24-hour windows. Requests with valid wallet addresses consume the IP allowance even when subsequent processing fails or no transaction is needed.

Wallet grants are recorded before transaction submission to reduce duplicate grants during slow confirmations. A failed submission can therefore leave the wallet marked as funded for that day. KV reads and writes do not provide an atomic reservation, so these limits should be treated as best-effort abuse controls rather than strict guarantees under concurrent requests.
