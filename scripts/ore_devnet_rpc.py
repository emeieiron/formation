"""Pinned ORE/Entropy instruction and account helpers for the devnet operator.

The entry point supplies RPC and deployment addresses. No keys are loaded here.
"""

import base64
import json
import os
import struct
import time
from pathlib import Path

from Crypto.Hash import keccak
from solders.hash import Hash
from solders.instruction import AccountMeta, Instruction
from solders.message import Message
from solders.pubkey import Pubkey
from solders.transaction import Transaction

REVISIONS = {
    "ore": "48c203bd75db3cc45105ec29d8f8db719e5a2263",
    "entropy": "f26ae03cccab6188effb0a170b8123cf4bb54c94",
    "ore-mint": "67cbf80dc5c20d78a44a5169473d806f284297d2",
}
SYSTEM = Pubkey.default()
TOKEN = Pubkey.from_string("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
ATA = Pubkey.from_string("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
SLOT_HASHES = Pubkey.from_string("SysvarS1otHashes111111111111111111111111111")
SPLIT = Pubkey.from_string("SpLiT11111111111111111111111111111111111112")
U64_MAX = (1 << 64) - 1
OUT = Path(".")


def rpc(method, params=None):
    raise RuntimeError("Configure the devnet RPC first")


def save(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w") as stream:
        os.chmod(temporary, 0o600)
        json.dump(value, stream, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)


def keccak256(value):
    return keccak.new(digest_bits=256, data=value).digest()


def u64(value):
    return value.to_bytes(8, "little")


def pda(seeds, program=None):
    return Pubkey.find_program_address(seeds, ORE if program is None else program)[0]


def meta(key, signer=False, writable=True):
    return AccountMeta(key, signer, writable)


def round_key(round_id):
    return pda([b"round", u64(round_id)])


def miner(owner):
    return pda([b"miner", bytes(owner)])


def ata(owner):
    return pda([bytes(owner), bytes(TOKEN), bytes(MINT)], ATA)


def account(key):
    value = rpc(
        "getAccountInfo", [str(key), {"encoding": "base64", "commitment": "confirmed"}]
    )["value"]
    if value is not None:
        value["raw"] = base64.b64decode(value["data"][0])
    return value


def body(key, owner, discriminator):
    value = account(key)
    if (
        value is None
        or value["owner"] != str(owner)
        or value["raw"][:8] != bytes([discriminator]) + bytes(7)
    ):
        raise RuntimeError(f"Unexpected account owner or layout: {key}")
    return value["raw"][8:]


def board():
    raw = body(BOARD, ORE, 105)
    return dict(
        zip(
            ("round_id", "start_slot", "end_slot", "production_cost_ema"),
            struct.unpack("<4Q", raw),
        )
    )


def round_state(round_id):
    data = body(round_key(round_id), ORE, 109)
    if len(data) != 944:
        raise RuntimeError("Unexpected pinned Round layout")
    result = {"id": int.from_bytes(data[:8], "little")}
    for name, offset in [
        ("deployed", 8),
        ("mass", 208),
        ("count", 408),
        ("rewards", 688),
    ]:
        result[name] = list(struct.unpack("<25Q", data[offset : offset + 200]))
    result.update(
        entropy=data[608:640].hex(),
        expires_at=int.from_bytes(data[640:648], "little"),
        motherlode=int.from_bytes(data[648:656], "little"),
        rent_payer=str(Pubkey(data[656:688])),
        top_miner=str(Pubkey(data[912:944])),
    )
    return result


def miner_state(owner):
    data = body(miner(owner), ORE, 103)
    if len(data) != 744:
        raise RuntimeError("Unexpected pinned Miner layout")
    result = {"authority": str(Pubkey(data[:32]))}
    for name, offset in [("deployed", 56), ("mass", 256), ("cumulative", 456)]:
        result[name] = list(struct.unpack("<25Q", data[offset : offset + 200]))
    for name, offset in [
        ("checkpoint_id", 40),
        ("round_id", 656),
        ("rewards_sol", 680),
        ("refined_ore", 688),
        ("rewards_ore", 696),
    ]:
        result[name] = int.from_bytes(data[offset : offset + 8], "little")
    return result


def token_balance(key):
    value = account(key)
    return 0 if value is None else int.from_bytes(value["raw"][64:72], "little")


def reverse_bits(value):
    return int(f"{value:064b}"[::-1], 2)


def wait_slot(target):
    deadline = time.monotonic() + 180
    while rpc("getSlot", [{"commitment": "confirmed"}]) < target:
        if time.monotonic() > deadline:
            raise TimeoutError("Timed out waiting for a Solana slot")
        time.sleep(1)


def entropy_ix(code, signer, payload=b""):
    accounts = [meta(signer, True), meta(VAR)]
    if code == 5:
        accounts.append(meta(SLOT_HASHES, writable=False))
    return Instruction(ENTROPY, bytes([code]) + payload, accounts)


def reset_ix(payer, round_id, winner):
    return Instruction(
        ORE,
        bytes([9]),
        [
            meta(payer, True),
            meta(BOARD),
            meta(CONFIG),
            meta(FEE_COLLECTOR),
            meta(MINT),
            meta(round_key(round_id)),
            meta(round_key(round_id + 1)),
            meta(miner(winner)),
            meta(TREASURY),
            meta(ata(TREASURY)),
            meta(SYSTEM, writable=False),
            meta(TOKEN, writable=False),
            meta(ORE, writable=False),
            meta(SLOT_HASHES, writable=False),
            meta(VAR),
            meta(ENTROPY, writable=False),
            meta(MINT_AUTHORITY),
            meta(MINT_PROGRAM, writable=False),
        ],
    )


def deploy_ix(owner, amount, squares, round_id):
    mask = sum(1 << n for n in set(squares))
    return Instruction(
        ORE,
        bytes([6]) + u64(amount) + mask.to_bytes(4, "little"),
        [
            meta(owner, True),
            meta(owner),
            meta(pda([b"automation", bytes(owner)])),
            meta(BOARD),
            meta(CONFIG),
            meta(miner(owner)),
            meta(round_key(round_id)),
            meta(TREASURY),
            meta(SYSTEM, writable=False),
            meta(ORE, writable=False),
            meta(VAR),
            meta(ENTROPY, writable=False),
        ],
    )


def checkpoint_ix(payer, owner, round_id):
    return Instruction(
        ORE,
        bytes([2]),
        [
            meta(payer, True),
            meta(owner),
            meta(pda([b"automation", bytes(owner)])),
            meta(BOARD),
            meta(miner(owner)),
            meta(round_key(round_id)),
            meta(TREASURY),
            meta(SYSTEM, writable=False),
        ],
    )


def claim_ix(owner):
    return Instruction(
        ORE,
        bytes([4]) + u64(10_000),
        [
            meta(owner, True),
            meta(BOARD),
            meta(miner(owner)),
            meta(MINT),
            meta(ata(owner)),
            meta(TREASURY),
            meta(ata(TREASURY)),
            meta(SYSTEM, writable=False),
            meta(TOKEN, writable=False),
            meta(ATA, writable=False),
            meta(ORE, writable=False),
        ],
    )


def send(payer, label, instructions):
    """Persist exact signed bytes before dispatch; reconcile ambiguity on subsequent calls."""
    path = OUT / "pending" / f"{label}.json"
    entry = json.loads(path.read_text()) if path.exists() else None
    if entry is None:
        block = rpc("getLatestBlockhash", [{"commitment": "confirmed"}])["value"]
        tx = Transaction(
            [payer],
            Message(instructions, payer.pubkey()),
            Hash.from_string(block["blockhash"]),
        )
        entry = {
            "signature": str(tx.signatures[0]),
            "bytes": base64.b64encode(bytes(tx)).decode(),
            "valid_until": block["lastValidBlockHeight"],
        }
        save(path, entry)
    signature = entry["signature"]
    deadline = time.monotonic() + 100
    while time.monotonic() < deadline:
        status = rpc(
            "getSignatureStatuses", [[signature], {"searchTransactionHistory": True}]
        )["value"][0]
        if status and status["err"] is not None:
            path.unlink()
            raise RuntimeError(f"{label} failed: {status['err']}")
        if status and status.get("confirmationStatus") in ("confirmed", "finalized"):
            receipt = rpc(
                "getTransaction",
                [
                    signature,
                    {
                        "encoding": "json",
                        "commitment": "confirmed",
                        "maxSupportedTransactionVersion": 0,
                    },
                ],
            )
            if receipt:
                save(OUT / "receipts" / f"{label}-{signature}.json", receipt)
                return {
                    "label": label,
                    "signature": signature,
                    "slot": receipt["slot"],
                    "fee_lamports": receipt["meta"]["fee"],
                }
        elif status is None:
            if (
                rpc("getBlockHeight", [{"commitment": "confirmed"}])
                > entry["valid_until"]
            ):
                # Re-read status at expiry, avoiding a race with a confirmation.
                if (
                    rpc(
                        "getSignatureStatuses",
                        [[signature], {"searchTransactionHistory": True}],
                    )["value"][0]
                    is None
                ):
                    path.unlink()
                    raise RuntimeError(
                        f"{label} expired; reconcile state before trying again"
                    )
            else:
                rpc(
                    "sendTransaction",
                    [
                        entry["bytes"],
                        {"encoding": "base64", "preflightCommitment": "confirmed"},
                    ],
                )
        time.sleep(2)
    raise TimeoutError(f"Reconcile pending {label}: {signature}")


def expected_rewards(round_data, miner_data):
    rng = 0
    for limb in struct.unpack("<4Q", bytes.fromhex(round_data["entropy"])):
        rng ^= limb
    winning = rng % 25
    total_winning = round_data["deployed"][winning]
    sample = reverse_bits(rng) % total_winning if total_winning else 0
    sol = ore = 0
    for index, amount in enumerate(miner_data["deployed"]):
        if not amount:
            continue
        total = round_data["deployed"][index]
        admin = max(1, total // 100)
        protocol = max(1, (total - admin) // 10) if index != winning else 0
        sol += amount * (total - admin - protocol) // total
        if index == winning:
            if round_data["top_miner"] == str(SPLIT):
                ore += sum(round_data["rewards"]) * amount // total
            elif (
                miner_data["cumulative"][index]
                <= sample
                < miner_data["cumulative"][index] + amount
            ):
                ore += sum(round_data["rewards"])
            ore += round_data["motherlode"] * amount // total
    return sol, ore, winning
