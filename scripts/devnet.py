#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = ["solders>=0.21"]
# ///
"""Sets up the Formation vault on devnet and funds sponsor contests. Deploy the program first (see README), then:

  scripts/devnet.py setup                      test SKR mint, test Genesis Token group owned by the vault, vault config
  scripts/devnet.py settings [KEY=VALUE ...]   show the vault's settings, or change some (admin only)
  scripts/devnet.py test-token WALLET          give WALLET its test Genesis Token and SOL for fees, as the faucet does
  scripts/devnet.py contest [options]          fund a contest; see `contest --help`
  scripts/devnet.py draw CONTEST               request a draw's randomness from ORAO and finalize it
  scripts/devnet.py fund WALLET [SOL]

Signs with $FORMATION_KEYPAIR (default ~/.config/solana/seekers-devnet.json), which must be the program's
upgrade authority for `setup` and its admin for `settings`. Addresses are kept in program/devnet.json.
"""
import argparse
import base64
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.request

from solders.hash import Hash
from solders.instruction import AccountMeta, Instruction
from solders.keypair import Keypair
from solders.message import Message
from solders.pubkey import Pubkey
from solders.transaction import Transaction

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STATE = os.environ.get("FORMATION_STATE", os.path.join(ROOT, "program", "devnet.json"))
IDL = os.path.join(ROOT, "program", "formation-vault", "idl", "formation_vault.json")
RPC = os.environ.get("FORMATION_RPC", "https://api.devnet.solana.com")
KEYPAIR = os.path.expanduser(os.environ.get("FORMATION_KEYPAIR", "~/.config/solana/seekers-devnet.json"))

PROGRAM = Pubkey.from_string(json.load(open(IDL))["address"])
TOKEN = Pubkey.from_string("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
TOKEN_2022 = Pubkey.from_string("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
ATA_PROGRAM = Pubkey.from_string("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
SYSTEM = Pubkey.from_string("11111111111111111111111111111111")
LOADER = Pubkey.from_string("BPFLoaderUpgradeab1e11111111111111111111111")
VRF = Pubkey.from_string("VRFzZoJdhFWL8rkvu87LpKM3RbcVezpMEc6X5GVDr7y")
SKR = 1_000_000
DAY = 86_400

# (name, borsh type, default) in the order of the program's Settings struct.
SETTINGS = [
    ("paused", "bool", False),
    ("modes", "u8", 3),
    ("owner_weight", "u16", 3),
    ("min_guest_share", "u64", 10 * SKR),
    ("min_budgets", "u16", 10),
    ("max_per_key", "u8", 3),
    ("max_wins_per_sgt", "u8", 3),
    ("guest_ceiling", "u8", 64),
    ("claim_window", "i64", 30 * DAY),
    ("min_play_window", "i64", 3_600),
    ("max_play_window", "i64", 90 * DAY),
    ("min_enter_window", "i64", 3_600),
    ("max_enter_window", "i64", 30 * DAY),
    ("draw_timeout", "i64", 3_600),
    # Devnet has no real Genesis Tokens, so any wallet may mint itself a test one.
    ("test_tokens", "bool", True),
]
SIZES = {"bool": 1, "u8": 1, "u16": 2, "u32": 4, "u64": 8, "i64": 8, "pubkey": 32}
# The Contest account up to its draw state; the mode is a fixed u8 so these offsets never move.
CONTEST = [("sponsor", "pubkey"), ("nonce", "u64"), ("mint", "pubkey"), ("vault", "pubkey"), ("sgt_group", "pubkey"),
           ("vrf_program", "pubkey"), ("mode", "u8"), ("draw_bps", "u16"), ("only", "pubkey"), ("wins_per_sgt", "u8"),
           ("pool", "u64"), ("unallocated", "u64"), ("budget", "u64"), ("max_guests", "u8"), *[(n, t) for n, t, _ in SETTINGS],
           ("created_at", "i64"), ("enter_until", "i64"), ("play_until", "i64"), ("entered", "u32"), ("selected", "u32"),
           ("unlocks", "u32"), ("draw_seed", 32), ("requested_at", "i64"), ("attempts", "u8"), ("randomness", 32), ("done", "bool")]


def rpc(method, params):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    req = urllib.request.Request(RPC, body, {"Content-Type": "application/json"})
    reply = json.load(urllib.request.urlopen(req, timeout=30))
    if "error" in reply:
        raise SystemExit(f"{method}: {reply['error'].get('message')}\n" + "\n".join(reply["error"].get("data", {}).get("logs") or []))
    return reply["result"]


def account(key):
    value = rpc("getAccountInfo", [str(key), {"encoding": "base64", "commitment": "confirmed"}])["value"]
    return (Pubkey.from_string(value["owner"]), base64.b64decode(value["data"][0])) if value else None


def discriminator(name):
    return bytes(next(i for i in json.load(open(IDL))["instructions"] if i["name"] == name)["discriminator"])


def encode(kind, value):
    if kind == "bool":
        return bytes([1 if value else 0])
    if kind == "pubkey":
        return bytes(value)
    return int(value).to_bytes(SIZES[kind], "little", signed=kind == "i64")


def decode(fields, data, at=8):
    out = {}
    for name, kind in fields:
        size = kind if isinstance(kind, int) else SIZES[kind]
        raw = data[at:at + size]
        out[name] = raw if isinstance(kind, int) else Pubkey(raw) if kind == "pubkey" else bool(raw[0]) if kind == "bool" \
            else int.from_bytes(raw, "little", signed=kind == "i64")
        at += size
    return out, at


def ata(owner, mint, program=TOKEN):
    return Pubkey.find_program_address([bytes(owner), bytes(program), bytes(mint)], ATA_PROGRAM)[0]


def config_pda():
    return Pubkey.find_program_address([b"config"], PROGRAM)[0]


def test_authority():
    return Pubkey.find_program_address([b"test-authority"], PROGRAM)[0]


def send(signer, *ixs):
    blockhash = Hash.from_string(rpc("getLatestBlockhash", [{"commitment": "confirmed"}])["value"]["blockhash"])
    tx = Transaction([signer], Message(list(ixs), signer.pubkey()), blockhash)
    sig = rpc("sendTransaction", [base64.b64encode(bytes(tx)).decode(), {"encoding": "base64", "preflightCommitment": "confirmed"}])
    for _ in range(60):
        status = rpc("getSignatureStatuses", [[sig]])["value"][0]
        if status and status.get("err"):
            raise SystemExit(f"{sig} failed: {status['err']}")
        if status and status.get("confirmationStatus") in ("confirmed", "finalized"):
            return sig
        time.sleep(1)
    raise SystemExit(f"{sig} not confirmed")


def spl(config, *args):
    out = subprocess.run(["spl-token", "-C", config, "--output", "json", *args], capture_output=True, text=True)
    if out.returncode:
        raise SystemExit(f"spl-token {' '.join(args)}\n{out.stderr or out.stdout}")
    return json.loads(out.stdout) if out.stdout.strip().startswith("{") else {}


def solana_config():
    path = os.path.join(ROOT, "program", "target", "formation-cli.yml")
    with open(path, "w") as f:
        # spl-token treats a config file without every field as missing.
        f.write(f'---\njson_rpc_url: {RPC}\nwebsocket_url: ""\nkeypair_path: {KEYPAIR}\naddress_labels: {{}}\ncommitment: confirmed\n')
    return path


def load_state():
    return json.load(open(STATE)) if os.path.exists(STATE) else {"seekers": {}}


def save_state(state):
    with open(STATE, "w") as f:
        json.dump(state, f, indent=2)


def settings_bytes(values):
    return b"".join(encode(kind, values[name]) for name, kind, _ in SETTINGS)


def setup(admin, config, state, _args):
    if "skr" not in state:
        state["skr"] = spl(config, "create-token", "--decimals", "6")["commandOutput"]["address"]
        save_state(state)
    if account(config_pda()) is None:
        # A fresh vault gets a fresh group, owned by the vault so it can mint test tokens.
        group = spl(config, "create-token", "--program-2022", "--enable-group", "--decimals", "0")["commandOutput"]["address"]
        spl(config, "initialize-group", group, "1000000", "--update-authority", str(test_authority()))
        state["group"] = group
        state["seekers"] = {}
        save_state(state)
        program_data = Pubkey.find_program_address([bytes(PROGRAM)], LOADER)[0]
        defaults = {name: default for name, _, default in SETTINGS}
        ix = Instruction(
            PROGRAM,
            discriminator("init_config") + bytes(Pubkey.from_string(state["group"])) + bytes(VRF) + settings_bytes(defaults),
            [
                AccountMeta(admin.pubkey(), True, True),
                AccountMeta(config_pda(), False, True),
                AccountMeta(Pubkey.from_string(state["skr"]), False, False),
                AccountMeta(PROGRAM, False, False),
                AccountMeta(program_data, False, False),
                AccountMeta(SYSTEM, False, False),
            ],
        )
        print("init_config", send(admin, ix))
    print(f"SKR {state['skr']}\nSGT group {state['group']}\nconfig {config_pda()}")


def current_settings():
    found = account(config_pda()) or sys.exit("Run `setup` first")
    # Config: admin, mint, SGT group, VRF program, then the settings.
    values, _ = decode([(n, t) for n, t, _ in SETTINGS], found[1], 8 + 4 * 32)
    return values


def settings(admin, _config, _state, args):
    values = current_settings()
    if args.changes:
        for change in args.changes:
            name, _, value = change.partition("=")
            kind = next((t for n, t, _ in SETTINGS if n == name), None) or sys.exit(f"Unknown setting {name}")
            values[name] = value.lower() in ("1", "true", "yes") if kind == "bool" else int(value)
        ix = Instruction(PROGRAM, discriminator("set_settings") + settings_bytes(values), [
            AccountMeta(admin.pubkey(), True, False),
            AccountMeta(config_pda(), False, True),
        ])
        print("set_settings", send(admin, ix))
    for name, value in values.items():
        print(f"{name} = {value}")


def test_token(admin, _config, state, args):
    wallet = Pubkey.from_string(args.wallet)
    mint = Pubkey.find_program_address([b"test-token", bytes(wallet)], PROGRAM)[0]
    token_account = ata(wallet, mint, TOKEN_2022)
    if account(mint) is None:
        ix = Instruction(PROGRAM, discriminator("mint_test_token"), [
            AccountMeta(admin.pubkey(), True, True),
            AccountMeta(wallet, False, False),
            AccountMeta(config_pda(), False, False),
            AccountMeta(test_authority(), False, False),
            AccountMeta(Pubkey.from_string(state["group"]), False, True),
            AccountMeta(mint, False, True),
            AccountMeta(token_account, False, True),
            AccountMeta(TOKEN_2022, False, False),
            AccountMeta(ATA_PROGRAM, False, False),
            AccountMeta(SYSTEM, False, False),
        ])
        print("mint_test_token", send(admin, ix))
    state["seekers"][args.wallet] = {"sgt": str(mint), "account": str(token_account)}
    save_state(state)
    transfer(admin, args.wallet, 0.05)
    print(f"{args.wallet} holds test Genesis Token {mint}")


def contest(admin, config, state, args):
    skr = Pubkey.from_string(state["skr"])
    only = Pubkey.default()
    if args.only:
        only = Pubkey.from_string((state["seekers"].get(args.only) or sys.exit(f"Run `seeker {args.only}` first"))["sgt"])
    now = int(time.time())
    if args.draw:
        mode = bytes([1]) + args.draw.to_bytes(2, "little")
        pool, budget = args.pool * SKR, 0
        enter_until = now + int(args.enter_hours * 3_600)
        play_until = enter_until + int(args.hours * 3_600)
    else:
        mode = bytes([0])
        budget = args.budget * SKR
        pool = budget * (1 if args.only else args.budgets)
        enter_until, play_until = 0, now + int(args.hours * 3_600)
    if account(ata(admin.pubkey(), skr)) is None:
        spl(config, "create-account", str(skr))
    spl(config, "mint", str(skr), str(pool // SKR))
    nonce = time.time_ns() // 1_000
    address = Pubkey.find_program_address([b"contest", bytes(admin.pubkey()), nonce.to_bytes(8, "little")], PROGRAM)[0]
    data = (discriminator("create_contest") + encode("u64", nonce) + encode("u64", pool) + mode + encode("u64", budget) + bytes(only)
            + bytes([args.wins]) + encode("i64", enter_until) + encode("i64", play_until)
            + args.title.encode()[:32].ljust(32, b"\0") + bytes(32))
    ix = Instruction(PROGRAM, data, [
        AccountMeta(admin.pubkey(), True, True),
        AccountMeta(config_pda(), False, False),
        AccountMeta(address, False, True),
        AccountMeta(ata(address, skr), False, True),
        AccountMeta(ata(admin.pubkey(), skr), False, True),
        AccountMeta(skr, False, False),
        AccountMeta(TOKEN, False, False),
        AccountMeta(ATA_PROGRAM, False, False),
        AccountMeta(SYSTEM, False, False),
    ])
    signature = send(admin, ix)
    state.setdefault("contests", []).append(str(address))
    save_state(state)
    kind = f"draw of {args.draw / 100:g}%" if args.draw else f"first come, {budget // SKR} SKR budgets" + (f" for {args.only}" if args.only else "")
    print(f"contest {address}: {pool // SKR} SKR, {kind}", signature)


def draw(admin, _config, _state, args):
    address = Pubkey.from_string(args.contest)
    c, _ = decode(CONTEST, (account(address) or sys.exit("No such contest"))[1])
    network = Pubkey.find_program_address([b"orao-vrf-network-configuration"], VRF)[0]
    request = lambda seed: Pubkey.find_program_address([b"orao-vrf-randomness-request", seed], VRF)[0]
    if not c["done"]:
        if c["attempts"] == 0 or time.time() >= c["requested_at"] + c["draw_timeout"]:
            seed = hashlib.sha256(b"formation.draw" + bytes(address) + bytes([c["attempts"]])).digest()
            previous = request(c["draw_seed"]) if c["attempts"] else SYSTEM
            treasury = Pubkey(account(network)[1][40:72])
            ix = Instruction(PROGRAM, discriminator("request_draw"), [
                AccountMeta(admin.pubkey(), True, True),
                AccountMeta(address, False, True),
                AccountMeta(VRF, False, False),
                AccountMeta(network, False, True),
                AccountMeta(treasury, False, True),
                AccountMeta(request(seed), False, True),
                AccountMeta(previous, False, False),
                AccountMeta(SYSTEM, False, False),
            ])
            print("request_draw", send(admin, ix))
            c, _ = decode(CONTEST, account(address)[1])
        answer = request(c["draw_seed"])
        for _ in range(60):
            found = account(answer)
            # After its discriminator, a fulfilled ORAO request starts with variant 1.
            if found and found[1][8] == 1:
                break
            time.sleep(2)
        else:
            sys.exit("ORAO hasn't answered yet; run `draw` again later")
        ix = Instruction(PROGRAM, discriminator("finalize_draw"), [AccountMeta(address, False, True), AccountMeta(answer, False, False)])
        print("finalize_draw", send(admin, ix))
        c, _ = decode(CONTEST, account(address)[1])
    print(f"{c['selected']} of {c['entered']} entries selected, {c['budget'] / SKR:g} SKR each")


def transfer(admin, wallet, sol):
    ix = Instruction(SYSTEM, (2).to_bytes(4, "little") + int(sol * 1e9).to_bytes(8, "little"), [
        AccountMeta(admin.pubkey(), True, True),
        AccountMeta(Pubkey.from_string(wallet), False, True),
    ])
    print(f"sent {sol} SOL to {wallet}", send(admin, ix))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("setup").set_defaults(run=setup)
    p = commands.add_parser("settings")
    p.add_argument("changes", nargs="*", metavar="KEY=VALUE")
    p.set_defaults(run=settings)
    p = commands.add_parser("test-token")
    p.add_argument("wallet")
    p.set_defaults(run=test_token)
    p = commands.add_parser("contest", help="first come by default; --draw for a lottery")
    p.add_argument("--budget", type=int, default=300, help="SKR per Genesis Token, first come")
    p.add_argument("--budgets", type=int, default=10, help="how many budgets the pool holds, first come")
    p.add_argument("--only", metavar="WALLET", help="a contest only this wallet's test Genesis Token can unlock (run test-token first)")
    p.add_argument("--wins", type=int, default=1, help="budgets one Genesis Token may take, first come")
    p.add_argument("--draw", type=int, metavar="BPS", help="select this share of entrants, in basis points")
    p.add_argument("--pool", type=int, default=1_000, help="SKR in a draw's pool")
    p.add_argument("--enter-hours", type=float, default=1, help="how long a draw takes entries")
    p.add_argument("--hours", type=float, default=24, help="how long budgets can be played")
    p.add_argument("--title", default="")
    p.set_defaults(run=contest)
    p = commands.add_parser("draw")
    p.add_argument("contest")
    p.set_defaults(run=draw)
    p = commands.add_parser("fund")
    p.add_argument("wallet")
    p.add_argument("sol", type=float, nargs="?", default=0.2)
    p.set_defaults(run=lambda admin, _c, _s, a: transfer(admin, a.wallet, a.sol))
    args = parser.parse_args()
    admin = Keypair.from_bytes(bytes(json.load(open(KEYPAIR))))
    args.run(admin, solana_config(), load_state(), args)


if __name__ == "__main__":
    main()
