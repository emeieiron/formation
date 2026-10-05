#!/usr/bin/env python3
# Usage: scripts/localnet.py SEEKER_WALLET [OTHER_WALLET ...]
#
# Starts solana-test-validator with the vault program and its state preloaded as genesis accounts,
# so the app's Solana ledger can be tried without deploying anything: a config, a test SKR mint, a
# Seeker Genesis Token for SEEKER_WALLET in a test group, a contest only that token can unlock for each
# reward fixture, and SOL for fees for every wallet given. Emulators reach it at http://10.0.2.2:8899.
import base64
import hashlib
import json
import os
import subprocess
import sys
import time
from reward_fixtures import SKR, load_rewards

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "program", "target", "localnet")
SO = os.path.join(ROOT, "program", "target", "deploy", "formation_vault.so")

PROGRAM = json.load(open(os.path.join(ROOT, "program", "formation-vault", "idl", "formation_vault.json")))["address"]
TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
ATA_PROGRAM = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
SYSTEM = "11111111111111111111111111111111"
VRF = "VRFzZoJdhFWL8rkvu87LpKM3RbcVezpMEc6X5GVDr7y"
OWNER_WEIGHT, MIN_GUEST_SHARE, CLAIM_WINDOW = 3, 10 * SKR, 30 * 86_400

ALPHABET = b"123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"


def b58encode(b):
    n = int.from_bytes(b, "big")
    out = bytearray()
    while n:
        n, r = divmod(n, 58)
        out.append(ALPHABET[r])
    return (b"1" * (len(b) - len(b.lstrip(b"\0"))) + bytes(reversed(out))).decode()


def b58decode(s):
    n = 0
    for c in s.encode():
        n = n * 58 + ALPHABET.index(c)
    body = n.to_bytes((n.bit_length() + 7) // 8, "big") if n else b""
    return b"\0" * (len(s) - len(s.lstrip("1"))) + body


P = 2**255 - 19
D = (-121665 * pow(121666, P - 2, P)) % P


# A PDA must not decompress to an Ed25519 point: x² = (y² − 1) / (d·y² + 1) has no root mod p.
def on_curve(b):
    y = int.from_bytes(b, "little") & ((1 << 255) - 1)
    y2 = y * y % P
    x2 = (y2 - 1) * pow(D * y2 + 1, P - 2, P) % P
    return x2 == 0 or pow(x2, (P - 1) // 2, P) == 1


def pda(seeds, program):
    for bump in range(255, -1, -1):
        h = hashlib.sha256(b"".join(seeds) + bytes([bump]) + b58decode(program) + b"ProgramDerivedAddress").digest()
        if not on_curve(h):
            return h, bump
    raise ValueError("no PDA")


def ata(owner, mint, program=TOKEN):
    return pda([owner, b58decode(program), mint], ATA_PROGRAM)[0]


def label(name):
    return hashlib.sha256(b"formation-localnet/" + name.encode()).digest()


def u8(v):
    return v.to_bytes(1, "little")


def u16(v):
    return v.to_bytes(2, "little")


def u64(v):
    return v.to_bytes(8, "little", signed=v < 0)


def discriminator(account):
    return hashlib.sha256(f"account:{account}".encode()).digest()[:8]


def mint(authority, supply, decimals):
    return u16(1) + u16(0) + authority + u64(supply) + u8(decimals) + u8(1) + bytes(4) + bytes(32)


def token_account(mint_key, owner, amount):
    return mint_key + owner + u64(amount) + bytes(36) + u8(1) + bytes(12) + u64(0) + bytes(36)


def sgt_mint(key, group):
    base = bytes(36) + u64(1) + u8(0) + u8(1) + bytes(36)
    base = base + bytes(165 - len(base)) + u8(1)
    pointer = u16(22) + u16(64) + bytes(32) + key
    member = u16(23) + u16(72) + key + group + u64(1)
    return base + pointer + member


def u32(v):
    return v.to_bytes(4, "little")


# The vault's default settings, in the order of its Settings struct.
def settings():
    return (u8(0) + u8(3) + u16(OWNER_WEIGHT) + u64(MIN_GUEST_SHARE) + u16(10) + u8(3) + u8(3) + u8(64)
            + u64(CLAIM_WINDOW) + u64(3_600) + u64(90 * 86_400) + u64(3_600) + u64(30 * 86_400) + u64(3_600))


def title_bytes(text):
    raw = text.encode()[:32]
    return raw + bytes(32 - len(raw))


def dump(address, owner, data, lamports=None):
    if lamports is None:
        lamports = (len(data) + 128) * 3480 * 2
    path = os.path.join(OUT, f"{b58encode(address)}.json")
    body = {
        "pubkey": b58encode(address),
        "account": {
            "lamports": lamports,
            "data": [base64.b64encode(data).decode(), "base64"],
            "owner": owner,
            "executable": False,
            "rentEpoch": 0,
            "space": len(data),
        },
    }
    with open(path, "w") as f:
        json.dump(body, f)
    return b58encode(address), path


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__ or "usage: scripts/localnet.py SEEKER_WALLET [OTHER_WALLET ...]")
    if not os.path.exists(SO):
        sys.exit("Build the program first: cd program && anchor build")
    os.makedirs(OUT, exist_ok=True)
    seeker = b58decode(sys.argv[1])
    wallets = [seeker] + [b58decode(w) for w in sys.argv[2:]]
    accounts = []

    skr = label("skr")
    group = label("sgt-group")
    sponsor = label("sponsor")
    accounts.append(dump(skr, TOKEN, mint(label("skr-authority"), 10_000_000 * SKR, 6)))

    config, config_bump = pda([b"config"], PROGRAM)
    accounts.append(dump(config, PROGRAM, discriminator("Config") + label("admin") + skr + group + b58decode(VRF) + settings() + u8(config_bump)))

    sgt = label("sgt/" + sys.argv[1])
    sgt_account = label("sgt-account/" + sys.argv[1])
    accounts.append(dump(sgt, TOKEN_2022, sgt_mint(sgt, group)))
    accounts.append(dump(sgt_account, TOKEN_2022, token_account(sgt, seeker, 1)))

    fixtures = load_rewards()
    now = int(time.time())
    for nonce, fixture in enumerate(fixtures):
        address, bump = pda([b"contest", sponsor, u64(nonce)], PROGRAM)
        vault = ata(address, skr)
        budget = fixture.amount * SKR
        max_guests = min(budget // MIN_GUEST_SHARE - OWNER_WEIGHT, 64)
        play_until = now + fixture.days * 86_400
        data = (
            discriminator("Contest") + sponsor + u64(nonce) + skr + vault + group + b58decode(VRF)
            + u8(1) + u16(0) + sgt + u8(1) + u64(budget) + u64(budget) + u64(budget) + u8(max_guests) + settings()
            + u64(now) + u64(now) + u64(play_until) + u32(0) + u32(0) + u32(0)
            + bytes(32) + u64(0) + u8(0) + bytes(32) + u8(0) + title_bytes(fixture.title) + bytes(32) + u8(bump)
        )
        accounts.append(dump(address, PROGRAM, data))
        accounts.append(dump(vault, TOKEN, token_account(skr, address, budget)))

    for wallet in wallets:
        accounts.append(dump(wallet, SYSTEM, b"", lamports=10 * 1_000_000_000))

    args = ["solana-test-validator", "--reset", "--quiet", "--ledger", os.path.join(OUT, "ledger"), "--bpf-program", PROGRAM, SO]
    for address, path in accounts:
        args += ["--account", address, path]
    print(f"{len(fixtures)} contests funded for {sys.argv[1]}; SGT {b58encode(sgt)} in group {b58encode(group)}")
    print("RPC http://127.0.0.1:8899 (emulators: http://10.0.2.2:8899)", flush=True)
    os.execvp(args[0], args)


if __name__ == "__main__":
    main()
