import { keccak_256 } from "@noble/hashes/sha3";

// scripts/ore_devnet.py commits to a keccak hash chain grown from one random root, and Entropy reveals it backwards:
// each round's commit is the keccak of the seed it reveals. The root is the only secret the worker needs.
const CHAIN_LENGTH = 1024;

export function seedFor(rootHex: string, commit: Uint8Array): Uint8Array | null {
  let value: Uint8Array = Buffer.from(rootHex, "hex");
  if (value.length !== 32) throw new Error("ENTROPY_ROOT must be 32 bytes of hex");
  for (let i = 0; i <= CHAIN_LENGTH; i++) {
    const next = keccak_256(value);
    if (Buffer.from(next).equals(commit)) return value;
    value = next;
  }
  return null;
}
