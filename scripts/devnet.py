#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = ["solders>=0.21"]
# ///
# Sets up the Formation vault on devnet. Deploy the program first (see README), then:
#
#   scripts/devnet.py setup            test SKR mint, test Seeker Genesis Token group, vault config
#   scripts/devnet.py seeker WALLET    give WALLET a test Seeker Genesis Token and SOL for fees
#   scripts/devnet.py drops WALLET     lock rewards from $FORMATION_REWARDS for WALLET's token
#   scripts/devnet.py fund WALLET [SOL]
#
# Signs with $FORMATION_KEYPAIR (default ~/.config/solana/seekers-devnet.json), which must be the
# program's upgrade authority for `setup`. Addresses are kept in program/devnet.json.
import base64
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.request
import uuid

from solders.hash import Hash
from solders.instruction import AccountMeta, Instruction
from solders.keypair import Keypair
from solders.message import Message
from solders.pubkey import Pubkey
from solders.transaction import Transaction

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from reward_fixtures import SKR, load_rewards

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


def rpc(method, params):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    req = urllib.request.Request(RPC, body, {"Content-Type": "application/json"})
    reply = json.load(urllib.request.urlopen(req, timeout=30))
    if "error" in reply:
        raise SystemExit(f"{method}: {reply['error'].get('message')}\n" + "\n".join(reply["error"].get("data", {}).get("logs") or []))
    return reply["result"]


def discriminator(name):
    return bytes(next(i for i in json.load(open(IDL))["instructions"] if i["name"] == name)["discriminator"])


def ata(owner, mint, program=TOKEN):
    return Pubkey.find_program_address([bytes(owner), bytes(program), bytes(mint)], ATA_PROGRAM)[0]


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


def setup(admin, config, state):
    if "skr" not in state:
        state["skr"] = spl(config, "create-token", "--decimals", "6")["commandOutput"]["address"]
        save_state(state)
    if "group" not in state:
        group = spl(config, "create-token", "--program-2022", "--enable-group", "--decimals", "0")["commandOutput"]["address"]
        spl(config, "initialize-group", group, "1000000")
        state["group"] = group
        save_state(state)
    config_pda = Pubkey.find_program_address([b"config"], PROGRAM)[0]
    if rpc("getAccountInfo", [str(config_pda), {"encoding": "base64"}])["value"] is None:
        program_data = Pubkey.find_program_address([bytes(PROGRAM)], LOADER)[0]
        ix = Instruction(
            PROGRAM,
            discriminator("init_config") + bytes(Pubkey.from_string(state["group"])),
            [
                AccountMeta(admin.pubkey(), True, True),
                AccountMeta(config_pda, False, True),
                AccountMeta(Pubkey.from_string(state["skr"]), False, False),
                AccountMeta(PROGRAM, False, False),
                AccountMeta(program_data, False, False),
                AccountMeta(SYSTEM, False, False),
            ],
        )
        print("init_config", send(admin, ix))
    print(f"SKR {state['skr']}\nSGT group {state['group']}\nconfig {config_pda}")


def seeker(admin, config, state, wallet):
    if wallet not in state["seekers"]:
        mint = spl(config, "create-token", "--program-2022", "--enable-member", "--decimals", "0")["commandOutput"]["address"]
        spl(config, "initialize-member", mint, state["group"])
        spl(config, "create-account", mint, "--owner", wallet, "--fee-payer", KEYPAIR)
        account = str(ata(Pubkey.from_string(wallet), Pubkey.from_string(mint), TOKEN_2022))
        spl(config, "mint", mint, "1", account)
        state["seekers"][wallet] = {"sgt": mint, "account": account}
        save_state(state)
    fund(admin, wallet, 0.2)
    print(f"{wallet} holds test SGT {state['seekers'][wallet]['sgt']}")


def fund(admin, wallet, sol):
    ix = Instruction(SYSTEM, (2).to_bytes(4, "little") + int(sol * 1e9).to_bytes(8, "little"), [
        AccountMeta(admin.pubkey(), True, True),
        AccountMeta(Pubkey.from_string(wallet), False, True),
    ])
    print(f"sent {sol} SOL to {wallet}", send(admin, ix))


# Where the vault's reward account keeps the Genesis Token mint, the game's code and whether it's open.
OPPORTUNITY_SIZE, SGT_OFFSET, CHALLENGE_OFFSET, STATE_OFFSET = 337, 88, 195, 246


def open_games(mint):
    # One open reward per Seeker for each game, keyed by the Genesis Token's mint rather than the wallet, so moving
    # the token to another wallet doesn't earn it a second reward. The vault records the mint on every reward.
    accounts = rpc("getProgramAccounts", [str(PROGRAM), {"encoding": "base64", "filters": [
        {"dataSize": OPPORTUNITY_SIZE},
        {"memcmp": {"offset": SGT_OFFSET, "bytes": mint}},
        {"memcmp": {"offset": STATE_OFFSET, "bytes": "1"}},
    ]}])
    return {int.from_bytes(base64.b64decode(a["account"]["data"][0])[CHALLENGE_OFFSET:CHALLENGE_OFFSET + 2], "little") for a in accounts}


def drops(admin, config, state, wallet):
    sgt = state["seekers"].get(wallet) or sys.exit(f"Run `seeker {wallet}` first")
    funded = open_games(sgt["sgt"])
    fixtures = [f for f in load_rewards() if f.code not in funded]
    if not fixtures:
        print(f"{wallet}'s Genesis Token already has an open reward for each fixture" if funded else
              "Set FORMATION_REWARDS to a JSON fixture file for a registered game")
        return
    skr = Pubkey.from_string(state["skr"])
    total = sum(f.amount for f in fixtures)
    spl(config, "create-account", str(skr)) if rpc("getAccountInfo", [str(ata(admin.pubkey(), skr)), {"encoding": "base64"}])["value"] is None else None
    spl(config, "mint", str(skr), str(total))
    config_pda = Pubkey.find_program_address([b"config"], PROGRAM)[0]
    now = int(time.time())
    for fixture in fixtures:
        oid = uuid.uuid4().bytes
        opportunity = Pubkey.find_program_address([b"opportunity", oid], PROGRAM)[0]
        data = (
            discriminator("create") + oid + (fixture.amount * SKR).to_bytes(8, "little") + bytes([fixture.players]) + fixture.owner_bps.to_bytes(2, "little")
            + fixture.code.to_bytes(2, "little") + bytes([fixture.difficulty]) + fixture.title.encode().ljust(32, b"\0") + (now + fixture.days * 86_400).to_bytes(8, "little")
        )
        ix = Instruction(PROGRAM, data, [
            AccountMeta(admin.pubkey(), True, True),
            AccountMeta(config_pda, False, False),
            AccountMeta(Pubkey.from_string(wallet), False, False),
            AccountMeta(Pubkey.from_string(sgt["sgt"]), False, False),
            AccountMeta(Pubkey.from_string(sgt["account"]), False, False),
            AccountMeta(opportunity, False, True),
            AccountMeta(ata(opportunity, skr), False, True),
            AccountMeta(ata(admin.pubkey(), skr), False, True),
            AccountMeta(skr, False, False),
            AccountMeta(TOKEN, False, False),
            AccountMeta(ATA_PROGRAM, False, False),
            AccountMeta(SYSTEM, False, False),
        ])
        print(f"locked {fixture.amount} SKR ({fixture.players} players) as {opportunity}", send(admin, ix))


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in ("setup", "seeker", "drops", "fund") or (sys.argv[1] != "setup" and len(sys.argv) < 3):
        sys.exit(__doc__ or "usage: scripts/devnet.py setup | seeker WALLET | drops WALLET | fund WALLET [SOL]")
    admin = Keypair.from_bytes(bytes(json.load(open(KEYPAIR))))
    config = solana_config()
    state = load_state()
    command = sys.argv[1]
    if command == "setup":
        setup(admin, config, state)
    elif command == "seeker":
        seeker(admin, config, state, sys.argv[2])
    elif command == "drops":
        drops(admin, config, state, sys.argv[2])
    else:
        fund(admin, sys.argv[2], float(sys.argv[3]) if len(sys.argv) > 3 else 0.2)


if __name__ == "__main__":
    main()
