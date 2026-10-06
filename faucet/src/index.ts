import {
  Connection,
  Keypair,
  PublicKey,
  SystemProgram,
  Transaction,
  TransactionInstruction,
} from "@solana/web3.js";

interface Env {
  PROGRAM_ID: string;
  GRANT_LAMPORTS: string;
  REQUESTS_PER_IP_PER_DAY: string;
  // Secrets: the devnet RPC URL (the public node refuses Cloudflare's network) and the faucet's keypair.
  RPC_URL: string;
  FAUCET_KEY: string;
  LIMITS: KVNamespace;
}

const TOKEN_2022 = new PublicKey("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb");
const ATA_PROGRAM = new PublicKey("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");
const MINT_TEST_TOKEN = Buffer.from([173, 1, 219, 194, 158, 97, 116, 40]);
// Config: discriminator, admin, mint, then the Genesis Token group.
const GROUP_OFFSET = 8 + 32 + 32;
const DAY_SECONDS = 86_400;

// POST {"wallet": "<base58>"}: sends a test Seeker's wallet devnet SOL for fees, at most once a day, and mints
// it a test Genesis Token if it has none. The faucet holds the SOL and pays every fee.
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method !== "POST") return reply(405, { error: "POST a wallet address" });
    let wallet: PublicKey;
    try {
      wallet = new PublicKey(((await request.json()) as { wallet?: string }).wallet ?? "");
    } catch {
      return reply(400, { error: "That isn't a Solana address" });
    }

    const day = Math.floor(Date.now() / 1000 / DAY_SECONDS);
    const ipKey = `ip:${request.headers.get("CF-Connecting-IP") ?? "unknown"}:${day}`;
    const used = Number((await env.LIMITS.get(ipKey)) ?? "0");
    if (used >= Number(env.REQUESTS_PER_IP_PER_DAY)) return reply(429, { error: "Too many requests today; try again tomorrow" });
    await env.LIMITS.put(ipKey, String(used + 1), { expirationTtl: DAY_SECONDS });

    try {
      const connection = new Connection(env.RPC_URL, "confirmed");
      const faucet = Keypair.fromSecretKey(Uint8Array.from(JSON.parse(env.FAUCET_KEY)));
      const program = new PublicKey(env.PROGRAM_ID);
      const pda = (...seeds: Uint8Array[]) => PublicKey.findProgramAddressSync(seeds, program)[0];
      const mint = pda(text("test-token"), wallet.toBytes());
      const tx = new Transaction();

      const solKey = `sol:${wallet.toBase58()}:${day}`;
      const grantSol = !(await env.LIMITS.get(solKey));
      if (grantSol) {
        tx.add(SystemProgram.transfer({ fromPubkey: faucet.publicKey, toPubkey: wallet, lamports: Number(env.GRANT_LAMPORTS) }));
      }
      if ((await connection.getAccountInfo(mint)) === null) {
        const config = pda(text("config"));
        const configAccount = await connection.getAccountInfo(config);
        if (!configAccount) return reply(503, { error: "The Formation vault isn't set up on this network" });
        const group = new PublicKey(configAccount.data.subarray(GROUP_OFFSET, GROUP_OFFSET + 32));
        const tokenAccount = PublicKey.findProgramAddressSync([wallet.toBytes(), TOKEN_2022.toBytes(), mint.toBytes()], ATA_PROGRAM)[0];
        tx.add(
          new TransactionInstruction({
            programId: program,
            data: MINT_TEST_TOKEN,
            keys: [
              { pubkey: faucet.publicKey, isSigner: true, isWritable: true },
              { pubkey: wallet, isSigner: false, isWritable: false },
              { pubkey: config, isSigner: false, isWritable: false },
              { pubkey: pda(text("test-authority")), isSigner: false, isWritable: false },
              { pubkey: group, isSigner: false, isWritable: true },
              { pubkey: mint, isSigner: false, isWritable: true },
              { pubkey: tokenAccount, isSigner: false, isWritable: true },
              { pubkey: TOKEN_2022, isSigner: false, isWritable: false },
              { pubkey: ATA_PROGRAM, isSigner: false, isWritable: false },
              { pubkey: SystemProgram.programId, isSigner: false, isWritable: false },
            ],
          }),
        );
      }
      if (tx.instructions.length === 0) return reply(200, { mint: mint.toBase58(), signature: null });

      const { blockhash } = await connection.getLatestBlockhash();
      tx.recentBlockhash = blockhash;
      tx.feePayer = faucet.publicKey;
      tx.sign(faucet);
      // Recorded before sending, so a slow confirmation can't lead to a second grant.
      if (grantSol) await env.LIMITS.put(solKey, "1", { expirationTtl: DAY_SECONDS });
      const signature = await connection.sendRawTransaction(tx.serialize());
      // Workers have no websocket subscriptions, so poll the signature instead.
      for (let attempt = 0; attempt < 40; attempt++) {
        const status = (await connection.getSignatureStatuses([signature])).value[0];
        if (status?.err) return reply(502, { error: "The faucet's transaction failed", signature });
        if (status?.confirmationStatus === "confirmed" || status?.confirmationStatus === "finalized") {
          return reply(200, { mint: mint.toBase58(), signature });
        }
        await new Promise((resolve) => setTimeout(resolve, 750));
      }
      return reply(504, { error: "The faucet's transaction hasn't confirmed yet; try again shortly", signature });
    } catch (e) {
      return reply(502, { error: `The faucet couldn't send: ${e instanceof Error ? e.message : String(e)}` });
    }
  },
};

function text(value: string): Uint8Array {
  return new TextEncoder().encode(value);
}

function reply(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}
