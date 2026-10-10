import { DurableObject } from "cloudflare:workers";
import bs58 from "bs58";
import { Connection, Keypair, PublicKey, Transaction, TransactionInstruction } from "@solana/web3.js";
import { seedFor } from "./entropy";
import * as ore from "./ore";

interface Env {
  ORE_PROGRAM: string;
  ENTROPY_PROGRAM: string;
  ORE_MINT_PROGRAM: string;
  ORE_MINT: string;
  FEE_COLLECTOR: string;
  MIN_BALANCE_LAMPORTS: string;
  // Secrets: the devnet RPC URL (the public node refuses Cloudflare's network), the worker's fee-paying keypair
  // and the root of the entropy hash chain.
  RPC_URL: string;
  WORKER_KEY: string;
  ENTROPY_ROOT: string;
  ROUNDS: DurableObjectNamespace<OreRounds>;
}

const DEVNET_GENESIS = "EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG";
// ORE's reset waits this many slots after a round ends; matches the deployment's intermission.
const INTERMISSION_SLOTS = 5n;
const SLOT_MS = 400;
const ACTIVE_MS = 2_000;
const IDLE_MS = 5_000;
const PAUSED_MS = 60_000;

interface Step {
  phase: string;
  wait: number;
}

interface Pending {
  label: string;
  signature: string;
  tx: string;
  validUntil: number;
}

interface Health {
  phase: string;
  error: string | null;
  worker: string;
  checkedAt: string;
  lastResolvedRound: string | null;
  samplesRemaining: string | null;
}

// Pauses the loop until an operator acts, without counting as a failure.
class Paused extends Error {}

// GET returns the worker's health. The cron trigger restarts the alarm loop if a deploy or eviction dropped it.
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method !== "GET") return reply(405, { error: "GET the worker's status" });
    return rounds(env).fetch("https://ore-worker/status");
  },
  async scheduled(_controller: ScheduledController, env: Env): Promise<void> {
    await rounds(env).fetch("https://ore-worker/start", { method: "POST" });
  },
};

// Resolves player-started ORE rounds on devnet, the job `scripts/ore_devnet.py worker` does locally. One instance
// runs an alarm loop: each step reads the chain, sends at most one transaction and re-arms. The pending
// transaction is stored before it's sent, so a restart reconciles it instead of sending a second one.
export class OreRounds extends DurableObject<Env> {
  private connection = new Connection(this.env.RPC_URL, "confirmed");
  private worker = Keypair.fromSecretKey(Uint8Array.from(JSON.parse(this.env.WORKER_KEY)));
  private deployment = ore.deployment(
    new PublicKey(this.env.ORE_PROGRAM),
    new PublicKey(this.env.ENTROPY_PROGRAM),
    new PublicKey(this.env.ORE_MINT_PROGRAM),
    new PublicKey(this.env.ORE_MINT),
    new PublicKey(this.env.FEE_COLLECTOR),
  );
  private devnet = false;

  async fetch(request: Request): Promise<Response> {
    if (new URL(request.url).pathname === "/start") {
      if ((await this.ctx.storage.getAlarm()) === null) await this.ctx.storage.setAlarm(Date.now());
      return reply(200, { started: true });
    }
    const health = await this.ctx.storage.get<Health>("health");
    return reply(200, health ?? { phase: "starting", worker: this.worker.publicKey.toBase58() });
  }

  async alarm(): Promise<void> {
    const storage = this.ctx.storage;
    const failures = (await storage.get<number>("failures")) ?? 0;
    let wait: number;
    let health: Partial<Health>;
    try {
      const step = await this.step();
      await storage.put("failures", 0);
      wait = step.wait;
      health = { phase: step.phase, error: null };
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      if (e instanceof Paused) {
        wait = PAUSED_MS;
        health = { phase: "paused", error: message };
      } else {
        await storage.put("failures", failures + 1);
        wait = Math.min(30_000, 3_000 * 2 ** Math.min(failures + 1, 4));
        health = { phase: "retrying", error: message.slice(0, 300) };
      }
    }
    const previous = await storage.get<Health>("health");
    await storage.put("health", {
      lastResolvedRound: null,
      samplesRemaining: null,
      ...previous,
      ...health,
      worker: this.worker.publicKey.toBase58(),
      checkedAt: new Date().toISOString(),
    });
    await storage.setAlarm(Date.now() + wait);
  }

  private async step(): Promise<Step> {
    if (!this.devnet) {
      if ((await this.connection.getGenesisHash()) !== DEVNET_GENESIS) throw new Paused("RPC_URL isn't a devnet endpoint");
      this.devnet = true;
    }
    const pending = await this.ctx.storage.get<Pending>("pending");
    if (pending) return this.reconcile(pending);

    const d = this.deployment;
    const board = await ore.board(this.connection, d);
    // A round starts with its first deploy; until a player mines, there's nothing to resolve.
    if (board.endSlot === ore.U64_MAX) return { phase: "idle", wait: IDLE_MS };
    const slot = BigInt(await this.connection.getSlot("confirmed"));
    const ready = board.endSlot + INTERMISSION_SLOTS;
    if (slot < ready) return { phase: "waiting", wait: untilSlot(slot, ready) };

    const balance = await this.connection.getBalance(this.worker.publicKey, "confirmed");
    if (balance < Number(this.env.MIN_BALANCE_LAMPORTS)) {
      throw new Paused(`Worker balance is low; fund ${this.worker.publicKey.toBase58()} with devnet SOL`);
    }
    const entropy = await ore.entropyVar(this.connection, d);
    await this.record({ samplesRemaining: entropy.samples.toString() });
    if (entropy.samples < 2n) throw new Paused("Entropy samples are exhausted; open a new entropy var");

    if (isZero(entropy.value)) {
      if (slot <= entropy.endAt) return { phase: "waiting", wait: untilSlot(slot, entropy.endAt + 1n) };
      const signer = this.worker.publicKey;
      if (isZero(entropy.slotHash)) return this.send(`r${board.roundId}-entropy-sample`, [ore.sampleInstruction(d, signer)]);
      if (isZero(entropy.seed)) {
        const seed = seedFor(this.env.ENTROPY_ROOT, entropy.commit);
        if (!seed) throw new Paused("The entropy commit isn't in ENTROPY_ROOT's hash chain");
        return this.send(`r${board.roundId}-entropy-reveal`, [ore.revealInstruction(d, signer, seed)]);
      }
      throw new Error("Entropy has a seed and slot hash but no value");
    }

    const random = ore.rng(entropy.value);
    const winning = Number(random % 25n);
    const deployed = await ore.roundDeployed(this.connection, d, board.roundId);
    // With nothing on the winning tile, reset still needs a miner account; ORE accepts the signer's.
    const winner = deployed[winning] > 0n
      ? await ore.topMiner(this.connection, d, board.roundId, winning, deployed[winning], random)
      : this.worker.publicKey;
    return this.send(`r${board.roundId}-reset`, [ore.resetInstruction(d, this.worker.publicKey, board.roundId, winner)]);
  }

  private async send(label: string, instructions: TransactionInstruction[]): Promise<Step> {
    const { blockhash, lastValidBlockHeight } = await this.connection.getLatestBlockhash("confirmed");
    const tx = new Transaction({ blockhash, lastValidBlockHeight, feePayer: this.worker.publicKey }).add(...instructions);
    tx.sign(this.worker);
    const bytes = tx.serialize();
    const pending: Pending = {
      label,
      signature: bs58.encode(tx.signature!),
      tx: bytes.toString("base64"),
      validUntil: lastValidBlockHeight,
    };
    // Stored first: if the worker restarts mid-send, the next step reconciles this exact transaction.
    await this.ctx.storage.put("pending", pending);
    await this.connection.sendRawTransaction(bytes, { preflightCommitment: "confirmed" });
    return { phase: label, wait: ACTIVE_MS };
  }

  private async reconcile(pending: Pending): Promise<Step> {
    const status = (await this.connection.getSignatureStatuses([pending.signature], { searchTransactionHistory: true })).value[0];
    if (status?.err) {
      await this.ctx.storage.delete("pending");
      throw new Error(`${pending.label} failed: ${JSON.stringify(status.err)}`);
    }
    if (status?.confirmationStatus === "confirmed" || status?.confirmationStatus === "finalized") {
      await this.ctx.storage.delete("pending");
      const reset = /^r(\d+)-reset$/.exec(pending.label);
      if (reset) await this.record({ lastResolvedRound: reset[1] });
      return { phase: `${pending.label} confirmed`, wait: ACTIVE_MS };
    }
    if (!status) {
      if ((await this.connection.getBlockHeight("confirmed")) > pending.validUntil) {
        // Re-read at expiry so a late confirmation isn't mistaken for a drop.
        const late = (await this.connection.getSignatureStatuses([pending.signature], { searchTransactionHistory: true })).value[0];
        if (!late) {
          await this.ctx.storage.delete("pending");
          throw new Error(`${pending.label} expired before landing`);
        }
      } else {
        await this.connection.sendRawTransaction(Buffer.from(pending.tx, "base64"), { skipPreflight: true });
      }
    }
    return { phase: `confirming ${pending.label}`, wait: ACTIVE_MS };
  }

  private async record(fields: Partial<Health>): Promise<void> {
    const health = await this.ctx.storage.get<Health>("health");
    await this.ctx.storage.put("health", { ...health, ...fields });
  }
}

function rounds(env: Env): DurableObjectStub<OreRounds> {
  return env.ROUNDS.get(env.ROUNDS.idFromName("devnet"));
}

function untilSlot(slot: bigint, target: bigint): number {
  return Math.min(10_000, Math.max(1_000, Number(target - slot) * SLOT_MS));
}

function isZero(bytes: Uint8Array): boolean {
  return bytes.every((b) => b === 0);
}

function reply(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}
