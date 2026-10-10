import { AccountInfo, Connection, PublicKey, SystemProgram, TransactionInstruction } from "@solana/web3.js";

// Pinned to the ORE and Entropy revisions in program/ore-devnet.json, mirroring scripts/ore_devnet_rpc.py.
// Offsets below skip each account's 8-byte discriminator unless noted.

export const U64_MAX = 2n ** 64n - 1n;
const TOKEN = new PublicKey("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
const ATA = new PublicKey("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");
const SLOT_HASHES = new PublicKey("SysvarS1otHashes111111111111111111111111111");
const BOARD = 105;
const ROUND = 109;
const MINER = 103;
const VAR = 0;
const MINER_SIZE = 752;
const SAMPLE = 5;
const REVEAL = 4;
const RESET = 9;

export interface Deployment {
  ore: PublicKey;
  entropy: PublicKey;
  mintProgram: PublicKey;
  mint: PublicKey;
  feeCollector: PublicKey;
  board: PublicKey;
  config: PublicKey;
  treasury: PublicKey;
  var: PublicKey;
  mintAuthority: PublicKey;
}

export function deployment(ore: PublicKey, entropy: PublicKey, mintProgram: PublicKey, mint: PublicKey, feeCollector: PublicKey): Deployment {
  const board = pda(ore, text("board"));
  return {
    ore,
    entropy,
    mintProgram,
    mint,
    feeCollector,
    board,
    config: pda(ore, text("config")),
    treasury: pda(ore, text("treasury")),
    var: pda(entropy, text("var"), board.toBytes(), u64(0n)),
    mintAuthority: pda(mintProgram, text("authority")),
  };
}

export interface Board {
  roundId: bigint;
  endSlot: bigint;
}

export interface Var {
  commit: Buffer;
  seed: Buffer;
  slotHash: Buffer;
  value: Buffer;
  samples: bigint;
  endAt: bigint;
}

export async function board(connection: Connection, d: Deployment): Promise<Board> {
  const raw = body(await connection.getAccountInfo(d.board), d.board, d.ore, BOARD);
  return { roundId: raw.readBigUInt64LE(0), endSlot: raw.readBigUInt64LE(16) };
}

export async function entropyVar(connection: Connection, d: Deployment): Promise<Var> {
  const raw = body(await connection.getAccountInfo(d.var), d.var, d.entropy, VAR);
  return {
    commit: raw.subarray(72, 104),
    seed: raw.subarray(104, 136),
    slotHash: raw.subarray(136, 168),
    value: raw.subarray(168, 200),
    samples: raw.readBigUInt64LE(200),
    endAt: raw.readBigUInt64LE(224),
  };
}

// SOL deployed on each of the round's 25 tiles.
export async function roundDeployed(connection: Connection, d: Deployment, roundId: bigint): Promise<bigint[]> {
  const key = roundKey(d, roundId);
  const raw = body(await connection.getAccountInfo(key), key, d.ore, ROUND);
  if (raw.length !== 944) throw new Error("Unexpected pinned Round layout");
  return Array.from({ length: 25 }, (_, i) => raw.readBigUInt64LE(8 + i * 8));
}

// ORE's Round::rng and winning_square: XOR the value's four little-endian u64 limbs, then take it modulo 25.
export function rng(value: Buffer): bigint {
  let result = 0n;
  for (let limb = 0; limb < 4; limb++) result ^= value.readBigUInt64LE(limb * 8);
  return result;
}

// The miner whose cumulative range on the winning tile holds the round's sample, as ORE's reset expects.
export async function topMiner(connection: Connection, d: Deployment, roundId: bigint, winning: number, total: bigint, random: bigint): Promise<PublicKey> {
  const sample = reverseBits(random) % total;
  const miners = await connection.getProgramAccounts(d.ore, {
    commitment: "confirmed",
    filters: [
      { dataSize: MINER_SIZE },
      { memcmp: { offset: 0, encoding: "base64", bytes: Buffer.from([MINER, 0, 0, 0, 0, 0, 0, 0]).toString("base64") } },
      { memcmp: { offset: 664, encoding: "base64", bytes: Buffer.from(u64(roundId)).toString("base64") } },
    ],
  });
  for (const { pubkey, account } of miners) {
    // Raw offsets here: the filters above already matched the discriminator.
    const data = account.data;
    const deployed = data.readBigUInt64LE(64 + winning * 8);
    const cumulative = data.readBigUInt64LE(464 + winning * 8);
    if (cumulative <= sample && sample < cumulative + deployed) {
      const owner = new PublicKey(data.subarray(8, 40));
      if (!minerKey(d, owner).equals(pubkey)) throw new Error(`Miner account ${pubkey.toBase58()} isn't its owner's`);
      return owner;
    }
  }
  throw new Error(`Couldn't find round ${roundId}'s winning miner`);
}

export function sampleInstruction(d: Deployment, signer: PublicKey): TransactionInstruction {
  return new TransactionInstruction({
    programId: d.entropy,
    data: Buffer.from([SAMPLE]),
    keys: [signerMeta(signer), writable(d.var), readOnly(SLOT_HASHES)],
  });
}

export function revealInstruction(d: Deployment, signer: PublicKey, seed: Uint8Array): TransactionInstruction {
  return new TransactionInstruction({
    programId: d.entropy,
    data: Buffer.concat([Buffer.from([REVEAL]), seed]),
    keys: [signerMeta(signer), writable(d.var)],
  });
}

export function resetInstruction(d: Deployment, signer: PublicKey, roundId: bigint, winner: PublicKey): TransactionInstruction {
  return new TransactionInstruction({
    programId: d.ore,
    data: Buffer.from([RESET]),
    keys: [
      signerMeta(signer),
      writable(d.board),
      writable(d.config),
      writable(d.feeCollector),
      writable(d.mint),
      writable(roundKey(d, roundId)),
      writable(roundKey(d, roundId + 1n)),
      writable(minerKey(d, winner)),
      writable(d.treasury),
      writable(PublicKey.findProgramAddressSync([d.treasury.toBytes(), TOKEN.toBytes(), d.mint.toBytes()], ATA)[0]),
      readOnly(SystemProgram.programId),
      readOnly(TOKEN),
      readOnly(d.ore),
      readOnly(SLOT_HASHES),
      writable(d.var),
      readOnly(d.entropy),
      writable(d.mintAuthority),
      readOnly(d.mintProgram),
    ],
  });
}

function body(account: AccountInfo<Buffer> | null, key: PublicKey, owner: PublicKey, discriminator: number): Buffer {
  const data = account?.data;
  if (!account || !account.owner.equals(owner) || !data || data[0] !== discriminator || data.subarray(1, 8).some((b) => b !== 0)) {
    throw new Error(`Unexpected account owner or layout: ${key.toBase58()}`);
  }
  return data.subarray(8);
}

function roundKey(d: Deployment, roundId: bigint): PublicKey {
  return pda(d.ore, text("round"), u64(roundId));
}

function minerKey(d: Deployment, owner: PublicKey): PublicKey {
  return pda(d.ore, text("miner"), owner.toBytes());
}

function reverseBits(value: bigint): bigint {
  let result = 0n;
  for (let bit = 0; bit < 64; bit++) {
    result = (result << 1n) | (value & 1n);
    value >>= 1n;
  }
  return result;
}

function pda(program: PublicKey, ...seeds: Uint8Array[]): PublicKey {
  return PublicKey.findProgramAddressSync(seeds, program)[0];
}

function u64(value: bigint): Uint8Array {
  const bytes = Buffer.alloc(8);
  bytes.writeBigUInt64LE(value);
  return bytes;
}

function text(value: string): Uint8Array {
  return new TextEncoder().encode(value);
}

const signerMeta = (pubkey: PublicKey) => ({ pubkey, isSigner: true, isWritable: true });
const writable = (pubkey: PublicKey) => ({ pubkey, isSigner: false, isWritable: true });
const readOnly = (pubkey: PublicKey) => ({ pubkey, isSigner: false, isWritable: false });
