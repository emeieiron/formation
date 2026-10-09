#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = ["solders>=0.21,<0.29", "pycryptodome==3.23.0"]
# ///
"""Deploy a mining-only ORE fork to devnet; no Formation or Claim Jump changes.

Sources and private deployment artifacts stay in ignored program/target/ore-devnet.
Every network command targets devnet explicitly and checks its genesis hash.
"""

import argparse
import base64
import hashlib
import fcntl
import json
import os
from pathlib import Path
import shutil
import signal
import struct
import subprocess
import time
import tarfile
import urllib.request

from solders.keypair import Keypair
from solders.pubkey import Pubkey
from solders.instruction import Instruction
from solders.message import Message
from solders.transaction import Transaction
from solders.hash import Hash
from solders.system_program import create_account, CreateAccountParams

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "program/target/ore-devnet"
UPSTREAM_RPC = "https://api.devnet.solana.com"
RPC = os.environ.get("ORE_DEVNET_RPC", UPSTREAM_RPC)
GENESIS = "EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG"
PAYER = Path(
    os.environ.get("ORE_DEVNET_PAYER", "~/.config/solana/seekers-devnet.json")
).expanduser()
import ore_devnet_rpc as h


def rpc(method, params=None):
    request = urllib.request.Request(
        RPC,
        data=json.dumps(
            {"jsonrpc": "2.0", "id": 1, "method": method, "params": params or []}
        ).encode(),
        headers={"Content-Type": "application/json"},
    )
    for attempt in range(5):
        try:
            obj = json.load(urllib.request.urlopen(request, timeout=30))
            if "error" in obj:
                raise RuntimeError(f"{method}: {json.dumps(obj['error'])}")
            return obj["result"]
        except urllib.error.HTTPError as error:
            if error.code not in (429, 502, 503, 504) or attempt == 4:
                raise
            time.sleep(2**attempt)
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            if attempt == 4:
                raise
            time.sleep(2**attempt)


def guard():
    if rpc("getGenesisHash") != GENESIS:
        raise RuntimeError("Refusing a non-devnet chain")


def signer():
    return Keypair.from_bytes(bytes(json.loads(PAYER.read_text())))


def state():
    return json.loads((OUT / "state.json").read_text())


def configure():
    s = state()
    h.OUT = OUT
    h.rpc = rpc
    for field in (
        "ORE",
        "ENTROPY",
        "MINT_PROGRAM",
        "MINT",
        "ADMIN",
        "FEE_COLLECTOR",
        "BOARD",
        "CONFIG",
        "TREASURY",
        "VAR",
        "MINT_AUTHORITY",
    ):
        setattr(h, field, Pubkey.from_string(s["addresses"][field]))
    # The local helper's original default argument captures its canonical program ID.
    # Resolve the active deployment ID at each call for round/miner/automation PDAs.
    h.pda = lambda seeds, program=None: Pubkey.find_program_address(
        seeds, h.ORE if program is None else program
    )[0]
    return s


INITIALIZER = r"""
use ore_api::prelude::*;
use steel::*;

/// Devnet bootstrap only. Existing mining instructions remain unchanged.
pub fn process_initialize(accounts: &[AccountInfo<'_>], data: &[u8]) -> ProgramResult {
    if data.len() != 48 { return Err(ProgramError::InvalidInstructionData); }
    let clock = Clock::get()?;
    let [payer, board_info, config_info, treasury_info, round_info, provider, var_info, system, ore_program, entropy_program] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    payer.is_signer()?.has_address(&ADMIN_ADDRESS)?;
    system.is_program(&system_program::ID)?;
    ore_program.is_program(&ore_api::ID)?;
    entropy_program.is_program(&entropy_api::ID)?;
    board_info.is_empty()?.has_address(&BOARD_ADDRESS)?.has_seeds(&[BOARD], &ore_api::ID)?;
    config_info.is_empty()?.has_address(&CONFIG_ADDRESS)?.has_seeds(&[CONFIG], &ore_api::ID)?;
    treasury_info.is_empty()?.has_address(&TREASURY_ADDRESS)?.has_seeds(&[TREASURY], &ore_api::ID)?;
    round_info.is_empty()?.has_seeds(&[ROUND, &1u64.to_le_bytes()], &ore_api::ID)?;
    var_info.is_empty()?.has_address(&VAR_ADDRESS)?;
    let commit: [u8;32] = data[..32].try_into().unwrap();
    let samples = u64::from_le_bytes(data[32..40].try_into().unwrap());
    let slots = u64::from_le_bytes(data[40..48].try_into().unwrap());
    if commit == [0;32] || samples < 2 || !(16..=150).contains(&slots) {
        return Err(ProgramError::InvalidInstructionData);
    }
    create_program_account::<Board>(board_info, system, payer, &ore_api::ID, &[BOARD])?;
    create_program_account::<Config>(config_info, system, payer, &ore_api::ID, &[CONFIG])?;
    create_program_account::<Treasury>(treasury_info, system, payer, &ore_api::ID, &[TREASURY])?;
    create_program_account::<Round>(round_info, system, payer, &ore_api::ID, &[ROUND, &1u64.to_le_bytes()])?;
    let board = board_info.as_account_mut::<Board>(&ore_api::ID)?;
    board.round_id = 1;
    board.start_slot = clock.slot;
    board.end_slot = u64::MAX;
    board.production_cost_ema = 0;
    let config = config_info.as_account_mut::<Config>(&ore_api::ID)?;
    config.admin.authority = *payer.key;
    config.admin.fee_collector = ADMIN_FEE_COLLECTOR;
    config.admin.fee_rate = 100;
    config.protocol.authority = *payer.key;
    config.protocol.fee_collector = ADMIN_FEE_COLLECTOR;
    config.protocol.fee_rate = 1000;
    config.protocol.intermission_slots = 5;
    config.protocol.round_slots = 150;
    config.protocol.entropy_var_address = VAR_ADDRESS;
    config.protocol.entropy_program_id = entropy_api::ID;
    let round = round_info.as_account_mut::<Round>(&ore_api::ID)?;
    round.id = 1;
    round.expires_at = u64::MAX;
    round.rent_payer = *payer.key;
    invoke_signed(
        &entropy_api::sdk::open(*board_info.key, *payer.key, 0, *provider.key, commit, false, samples, clock.slot + slots),
        &[board_info.clone(), payer.clone(), provider.clone(), var_info.clone(), system.clone()],
        &entropy_api::ID, &[BOARD],
    )?;
    Ok(())
}
"""


def prepare():
    OUT.mkdir(parents=True, exist_ok=True)
    if (OUT / "state.json").exists():
        return configure()
    admin = signer().pubkey()
    keys = {}
    for name in ("ore", "entropy", "ore_mint", "mint"):
        key = Keypair()
        path = OUT / f"{name}-keypair.json"
        h.save(path, list(bytes(key)))
        path.chmod(0o600)
        keys[name] = str(key.pubkey())
    ore, entropy, mint_program = (
        Pubkey.from_string(keys[k]) for k in ("ore", "entropy", "ore_mint")
    )
    board = h.pda([b"board"], ore)
    addresses = {
        "ORE": str(ore),
        "ENTROPY": str(entropy),
        "MINT_PROGRAM": str(mint_program),
        "MINT": keys["mint"],
        "ADMIN": str(admin),
        "FEE_COLLECTOR": str(admin),
        "BOARD": str(board),
        "CONFIG": str(h.pda([b"config"], ore)),
        "TREASURY": str(h.pda([b"treasury"], ore)),
        "VAR": str(h.pda([b"var", bytes(board), h.u64(0)], entropy)),
        "MINT_AUTHORITY": str(h.pda([b"authority"], mint_program)),
    }
    chain = [os.urandom(32)]
    for _ in range(1024):
        chain.append(h.keccak256(chain[-1]))
    seeds = OUT / "entropy-seeds.json"
    h.save(seeds, [x.hex() for x in reversed(chain)])
    seeds.chmod(0o600)
    h.save(
        OUT / "state.json",
        {
            "rpc": RPC,
            "genesis_hash": GENESIS,
            "addresses": addresses,
            "programs": keys,
            "payer": str(admin),
            "payer_path": str(PAYER),
            "seed_cursor": 0,
            "upstream": h.REVISIONS,
        },
    )
    configure()
    return state()


def build():
    s = prepare()
    destination = OUT / "sources"
    destination.mkdir(exist_ok=True)
    for name in ("ore", "entropy", "ore-mint"):
        path = destination / name
        original = ROOT / "program/target/ore-localnet/upstream" / name
        if not original.exists():
            cache = OUT / "upstream"
            cache.mkdir(exist_ok=True)
            original = cache / name
            if not original.exists():
                archive_path = cache / f"{name}.tar.gz"
                request = urllib.request.Request(
                    f"https://codeload.github.com/regolith-labs/{name}/tar.gz/{h.REVISIONS[name]}",
                    headers={"User-Agent": "Formation-ORE-Devnet"},
                )
                with urllib.request.urlopen(request, timeout=60) as response:
                    archive_path.write_bytes(response.read())
                with tarfile.open(archive_path) as archive:
                    prefix = archive.getmembers()[0].name.split("/")[0]
                    archive.extractall(cache, filter="data")
                (cache / prefix).rename(original)
        shutil.copytree(
            original, path, dirs_exist_ok=True, ignore=shutil.ignore_patterns("target")
        )
    original = {
        "ORE": "oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv",
        "ENTROPY": "3jSkUuYBoJzQPMEzTvkDFXCZUBksPamrVhrnHR9igu2X",
        "MINT_PROGRAM": "mintzxW6Kckmeyh1h6Zfdj9QcYgCzhPSGiC8ChZ6fCx",
        "MINT": "oreoU2P8bN6jkk3jbaiVxYnG1dCXcYxwhwyK9jSybcp",
        "ADMIN": "HBUh9g46wk2X89CvaNN15UmsznP59rh6od1h8JwYAopk",
        "FEE_COLLECTOR": "DyB4Kv6V613gp2LWQTq1dwDYHGKuUEoDHnCouGUtxFiX",
        "BOARD": "BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi",
        "CONFIG": "9c9X7aDRAF41faiDs94ELjT19UrGnn72wBW9hPsS4Awy",
        "TREASURY": "45db2FSR4mcXdSVVZbKbwojU6uYDpMyhpEi7cC8nHaWG",
        "VAR": "BWCaDY96Xe4WkFq1M7UiCCRcChsJ3p51L5KrGzhxgm2E",
    }
    for file in destination.rglob("*.rs"):
        content = file.read_text()
        for field, old in original.items():
            content = content.replace(old, s["addresses"][field])
        file.write_text(content)
    cargo = destination / "ore/Cargo.toml"
    content = (
        cargo.read_text()
        .replace('entropy-api = "0.1.4"', 'entropy-api = { path = "../entropy/api" }')
        .replace(
            'ore-mint-api = "0.1.3"', 'ore-mint-api = { path = "../ore-mint/api" }'
        )
    )
    cargo.write_text(content)
    entropy_lib = destination / "entropy/program/src/lib.rs"
    content = entropy_lib.read_text()
    disabled = "// EntropyInstruction::Open => process_open(accounts, data)?,"
    assert disabled in content
    entropy_lib.write_text(
        content.replace(
            disabled, "EntropyInstruction::Open => process_open(accounts, data)?,"
        )
    )
    lib = destination / "ore/program/src/lib.rs"
    content = lib.read_text()
    content = "mod initialize;\n" + content
    needle = "    let (ix, data) = parse_instruction(&ore_api::ID, program_id, data)?;"
    assert needle in content
    content = content.replace(
        needle,
        "    if data.first() == Some(&250) {\n        if *program_id != ore_api::ID { return Err(ProgramError::IncorrectProgramId); }\n        return initialize::process_initialize(accounts, &data[1..]);\n    }\n"
        + needle,
    )
    lib.write_text(content)
    (destination / "ore/program/src/initialize.rs").write_text(INITIALIZER)
    builds = {}
    for name, binary in [
        ("entropy", "entropy_program"),
        ("ore-mint", "ore_mint"),
        ("ore", "ore"),
    ]:
        print(f"Building devnet {name}", flush=True)
        with (OUT / f"build-{name}.log").open("w") as log:
            subprocess.run(
                [
                    "cargo",
                    "+stable",
                    "build-sbf",
                    "--manifest-path",
                    "program/Cargo.toml",
                    "--sbf-out-dir",
                    str(OUT / "deploy"),
                ],
                cwd=destination / name,
                stdout=log,
                stderr=subprocess.STDOUT,
                check=True,
            )
        path = OUT / "deploy" / f"{binary}.so"
        builds[binary] = {
            "bytes": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        }
    h.save(
        OUT / "build.json",
        {
            "upstream": h.REVISIONS,
            "binaries": builds,
            "changes": "devnet addresses, local API dependencies, admin-only one-time ORE initializer, restored existing Entropy Open dispatch",
        },
    )


def deploy():
    guard()
    configure()
    for name, binary in [
        ("entropy", "entropy_program"),
        ("ore_mint", "ore_mint"),
        ("ore", "ore"),
    ]:
        program = h.account(Pubkey.from_string(state()["programs"][name]))
        image = (OUT / "deploy" / f"{binary}.so").read_bytes()
        if program and program["executable"]:
            program_data = h.account(Pubkey(program["raw"][4:36]))
            if program_data and program_data["raw"][45 : 45 + len(image)] == image:
                print(
                    f"{name} already deployed with matching binary; skipped.",
                    flush=True,
                )
                continue
        print(f"Deploying {name} to devnet", flush=True)
        buffer = OUT / f"{name}-buffer.json"
        if not buffer.exists():
            h.save(buffer, list(bytes(Keypair())))
            buffer.chmod(0o600)
        log_path = OUT / f"deploy-{name}.log"
        with log_path.open("w") as log:
            subprocess.run(
                [
                    "solana",
                    "program",
                    "deploy",
                    "--url",
                    RPC,
                    "--keypair",
                    str(PAYER),
                    "--upgrade-authority",
                    str(PAYER),
                    "--ws",
                    "wss://api.devnet.solana.com",
                    "--program-id",
                    str(OUT / f"{name}-keypair.json"),
                    "--buffer",
                    str(buffer),
                    "--use-tpu-client",
                    "--max-sign-attempts",
                    "10",
                    "--max-len",
                    str((OUT / "deploy" / f"{binary}.so").stat().st_size),
                    "--output",
                    "json",
                    str(OUT / "deploy" / f"{binary}.so"),
                ],
                stdout=log,
                stderr=subprocess.STDOUT,
                check=True,
            )
        print(log_path.read_text(), flush=True)


def initialize():
    guard()
    s = configure()
    payer = signer()
    if h.account(h.MINT) is None:
        mint_key = Keypair.from_bytes(
            bytes(json.loads((OUT / "mint-keypair.json").read_text()))
        )
        rent = rpc("getMinimumBalanceForRentExemption", [82])
        instructions = [
            create_account(
                CreateAccountParams(
                    from_pubkey=payer.pubkey(),
                    to_pubkey=mint_key.pubkey(),
                    lamports=rent,
                    space=82,
                    owner=h.TOKEN,
                )
            ),
            Instruction(
                h.TOKEN,
                bytes([20, 11]) + bytes(h.MINT_AUTHORITY) + bytes([0]),
                [h.meta(h.MINT)],
            ),
        ]
        block = Hash.from_string(
            rpc("getLatestBlockhash", [{"commitment": "confirmed"}])["value"][
                "blockhash"
            ]
        )
        tx = Transaction(
            [payer, mint_key], Message(instructions, payer.pubkey()), block
        )
        sig = rpc(
            "sendTransaction",
            [
                base64.b64encode(bytes(tx)).decode(),
                {"encoding": "base64", "preflightCommitment": "confirmed"},
            ],
        )
        wait_signature(sig)
        print("Created devnet mint:", sig, flush=True)
    if h.account(h.MINT_AUTHORITY) is None:
        h.send(
            payer,
            "mint-authority-init",
            [
                Instruction(
                    h.MINT_PROGRAM,
                    bytes([0]),
                    [
                        h.meta(payer.pubkey(), True),
                        h.meta(h.MINT_AUTHORITY),
                        h.meta(h.SYSTEM, writable=False),
                    ],
                )
            ],
        )
    if h.account(h.BOARD) is None:
        seeds = json.loads((OUT / "entropy-seeds.json").read_text())
        payload = h.keccak256(bytes.fromhex(seeds[0])) + h.u64(1024) + h.u64(32)
        ix = Instruction(
            h.ORE,
            bytes([250]) + payload,
            [
                h.meta(payer.pubkey(), True),
                h.meta(h.BOARD),
                h.meta(h.CONFIG),
                h.meta(h.TREASURY),
                h.meta(h.round_key(1)),
                h.meta(payer.pubkey()),
                h.meta(h.VAR),
                h.meta(h.SYSTEM, writable=False),
                h.meta(h.ORE, writable=False),
                h.meta(h.ENTROPY, writable=False),
            ],
        )
        h.send(payer, "protocol-initialize", [ix])
    if h.account(h.ata(h.TREASURY)) is None:
        ix = Instruction(
            h.ATA,
            bytes([1]),
            [
                h.meta(payer.pubkey(), True),
                h.meta(h.ata(h.TREASURY)),
                h.meta(h.TREASURY, writable=False),
                h.meta(h.MINT, writable=False),
                h.meta(h.SYSTEM, writable=False),
                h.meta(h.TOKEN, writable=False),
            ],
        )
        h.send(payer, "treasury-ata-create", [ix])
    finalize_entropy("bootstrap")
    print(
        json.dumps(
            {"initialized": True, "board": h.board(), "addresses": s["addresses"]},
            indent=2,
        ),
        flush=True,
    )


def wait_signature(sig):
    for _ in range(90):
        row = rpc("getSignatureStatuses", [[sig]])["value"][0]
        if row and row.get("confirmationStatus") in ("confirmed", "finalized"):
            if row["err"]:
                raise RuntimeError(f"{sig}: {row['err']}")
            return
        time.sleep(1)
    raise RuntimeError(f"Reconcile ambiguous transaction {sig}")


def finalize_entropy(label):
    s = state()
    payer = signer()
    var = h.body(h.VAR, h.ENTROPY, 0)
    seeds = json.loads((OUT / "entropy-seeds.json").read_text())
    if var[168:200] != bytes(32):
        s["seed_cursor"] = seeds.index(var[104:136].hex()) + 1
        h.save(OUT / "state.json", s)
        return
    end = int.from_bytes(var[224:232], "little")
    h.wait_slot(end + 1)
    if var[136:168] == bytes(32):
        h.send(payer, label + "-entropy-sample", [h.entropy_ix(5, payer.pubkey())])
    matches = [
        i
        for i, value in enumerate(seeds)
        if h.keccak256(bytes.fromhex(value)) == var[72:104]
    ]
    if len(matches) != 1:
        raise RuntimeError(
            "Entropy commitment does not match this deployment hash chain"
        )
    cursor = matches[0]
    seed = bytes.fromhex(seeds[cursor])
    var = h.body(h.VAR, h.ENTROPY, 0)
    if var[104:136] == bytes(32):
        h.send(
            payer, label + "-entropy-reveal", [h.entropy_ix(4, payer.pubkey(), seed)]
        )
    s["seed_cursor"] = cursor + 1
    h.save(OUT / "state.json", s)


def smoke(rounds, worker_managed=False):
    guard()
    s = configure()
    payer = signer()
    results = []
    for _ in range(rounds):
        rid = h.board()["round_id"]
        txs = []
        supply = int.from_bytes(h.account(h.MINT)["raw"][36:44], "little")
        txs.append(
            h.send(
                payer,
                f"r{rid}-deploy",
                [h.deploy_ix(payer.pubkey(), 25_000, list(range(25)), rid)],
            )
        )
        assert h.miner_state(payer.pubkey())["deployed"] == [25_000] * 25
        if worker_managed:
            deadline = time.monotonic() + 180
            while h.board()["round_id"] == rid:
                if time.monotonic() > deadline:
                    raise RuntimeError(f"Worker did not resolve round {rid}")
                time.sleep(3)
        else:
            h.wait_slot(h.board()["end_slot"] + 5)
            finalize_entropy(f"r{rid}")
            txs.append(
                h.send(
                    payer,
                    f"r{rid}-reset",
                    [h.reset_ix(payer.pubkey(), rid, payer.pubkey())],
                )
            )
        assert (
            int.from_bytes(h.account(h.MINT)["raw"][36:44], "little") - supply
            == 120_000_000_000
        )
        assert h.board()["round_id"] == rid + 1
        resolved = h.round_state(rid)
        prior = h.miner_state(payer.pubkey())
        expected_sol, expected_ore, winning = h.expected_rewards(resolved, prior)
        assert expected_ore >= 100_000_000_000
        wallet = h.account(payer.pubkey())["lamports"]
        checkpoint = h.send(
            payer,
            f"r{rid}-checkpoint",
            [h.checkpoint_ix(payer.pubkey(), payer.pubkey(), rid)],
        )
        txs.append(checkpoint)
        assert (
            h.account(payer.pubkey())["lamports"] - wallet
            == expected_sol - checkpoint["fee_lamports"]
        )
        assert h.miner_state(payer.pubkey())["rewards_ore"] == expected_ore
        before = h.token_balance(h.ata(payer.pubkey()))
        txs.append(h.send(payer, f"r{rid}-claim", [h.claim_ix(payer.pubkey())]))
        assert h.token_balance(h.ata(payer.pubkey())) - before == expected_ore
        row = {
            "round": rid,
            "worker_managed": worker_managed,
            "winning_claim": winning + 1,
            "mode": "split"
            if resolved["top_miner"] == str(h.SPLIT)
            else "single-miner",
            "returned_sol_lamports": expected_sol,
            "claimed_ore_units": expected_ore,
            "transactions": txs,
        }
        results.append(row)
        h.save(
            OUT / "verification.json",
            {
                "passed": True,
                "rounds": results,
                "addresses": s["addresses"],
                "genesis_hash": GENESIS,
            },
        )
        print(
            "PASS devnet mining, reset, checkpoint and claim:",
            json.dumps(row),
            flush=True,
        )


class BudgetExhausted(RuntimeError):
    pass


def worker(budget):
    """Resolve player-created rounds, bounded by a test-SOL operating budget."""
    guard()
    configure()
    payer = signer()
    lock = (OUT / "worker.lock").open("w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise RuntimeError("An ORE devnet worker is already running")
    (OUT / "worker.pid").write_text(str(os.getpid()) + "\n")
    start_balance = rpc(
        "getBalance", [str(payer.pubkey()), {"commitment": "confirmed"}]
    )["value"]
    floor = start_balance - budget

    def health(phase, error=None):
        h.save(
            OUT / "worker-health.json",
            {
                "pid": os.getpid(),
                "phase": phase,
                "error": error,
                "checked_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "budget_lamports": budget,
                "balance_floor": floor,
            },
        )
        if error:
            print(json.dumps({"worker": phase, "error": error}), flush=True)

    print(
        json.dumps(
            {
                "worker": "started",
                "budget_lamports": budget,
                "payer": str(payer.pubkey()),
            }
        ),
        flush=True,
    )

    def tick():
        board = h.board()
        if board["end_slot"] == h.U64_MAX:
            return "idle"
        if rpc("getSlot", [{"commitment": "confirmed"}]) < board["end_slot"] + 5:
            return "waiting"
        balance = rpc("getBalance", [str(payer.pubkey()), {"commitment": "confirmed"}])[
            "value"
        ]
        if balance - floor < 10_000_000:
            raise BudgetExhausted("Worker operating budget exhausted")
        rid = board["round_id"]
        if int.from_bytes(h.body(h.VAR, h.ENTROPY, 0)[200:208], "little") < 2:
            raise BudgetExhausted(
                "Entropy samples exhausted; operator intervention required"
            )
        finalize_entropy(f"r{rid}")
        raw = h.body(h.VAR, h.ENTROPY, 0)
        rng = 0
        for value in struct.unpack("<4Q", raw[168:200]):
            rng ^= value
        winning = rng % 25
        round_data = h.round_state(rid)
        owner = payer.pubkey()
        if round_data["deployed"][winning]:
            sample = h.reverse_bits(rng) % round_data["deployed"][winning]
            miners = rpc(
                "getProgramAccounts",
                [
                    str(h.ORE),
                    {
                        "encoding": "base64",
                        "commitment": "confirmed",
                        "filters": [{"dataSize": 752}],
                    },
                ],
            )
            owner = None
            for row in miners:
                data = base64.b64decode(row["account"]["data"][0])
                if data[0] != 103 or int.from_bytes(data[664:672], "little") != rid:
                    continue
                deployed = int.from_bytes(
                    data[64 + winning * 8 : 72 + winning * 8], "little"
                )
                cumulative = int.from_bytes(
                    data[464 + winning * 8 : 472 + winning * 8], "little"
                )
                if cumulative <= sample < cumulative + deployed:
                    owner = Pubkey(data[8:40])
                    assert str(h.miner(owner)) == row["pubkey"]
                    break
            if owner is None:
                raise RuntimeError(f"Could not reconcile round {rid} winning miner")
        if h.board()["round_id"] != rid:
            return "advanced"
        h.send(payer, f"r{rid}-worker-reset", [h.reset_ix(payer.pubkey(), rid, owner)])
        print(f"Worker resolved ORE round {rid}", flush=True)

        return "resolved"

    failures = 0
    try:
        while True:
            try:
                phase = tick()
                failures = 0
                health(phase)
            except BudgetExhausted as error:
                health("stopped", str(error))
                raise SystemExit(2)
            except Exception as error:
                failures += 1
                health("retrying", type(error).__name__ + ": " + str(error)[:300])
            time.sleep(min(30, 3 * 2 ** min(failures, 4)))
    except KeyboardInterrupt:
        health("stopped", "Operator stopped the worker")
    finally:
        (OUT / "worker.pid").unlink(missing_ok=True)


def stop_worker():
    path = OUT / "worker.pid"
    if not path.exists():
        return
    pid = int(path.read_text())
    command = subprocess.run(
        ["ps", "-p", str(pid), "-o", "command="], capture_output=True, text=True
    ).stdout
    if not command:
        path.unlink()
        return
    if "scripts/ore_devnet.py worker" not in command:
        raise RuntimeError(
            "Refusing to stop a process that is not the ORE devnet worker"
        )
    os.kill(pid, signal.SIGINT)
    path.unlink()
    print(f"Stopped ORE devnet worker PID {pid}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command",
        choices=[
            "build",
            "deploy",
            "initialize",
            "smoke",
            "status",
            "worker",
            "worker-stop",
        ],
    )
    parser.add_argument("--rounds", type=int, default=2)
    parser.add_argument("--worker-managed", action="store_true")
    parser.add_argument("--worker-budget-lamports", type=int, default=100_000_000)
    args = parser.parse_args()
    if args.command == "build":
        build()
    elif args.command == "deploy":
        deploy()
    elif args.command == "initialize":
        initialize()
    elif args.command == "worker":
        if not 10_000_000 <= args.worker_budget_lamports <= 1_000_000_000:
            parser.error("worker budget must be 0.01..1 test SOL")
        worker(args.worker_budget_lamports)
    elif args.command == "worker-stop":
        stop_worker()
    elif args.command == "smoke":
        if not 1 <= args.rounds <= 8:
            parser.error("--rounds must be 1..8")
        smoke(args.rounds, args.worker_managed)
    else:
        guard()
        s = configure()
        var = h.body(h.VAR, h.ENTROPY, 0)
        snapshot = {
            "health": rpc("getHealth"),
            "board": h.board(),
            "addresses": s["addresses"],
            "payer_balance_lamports": rpc(
                "getBalance", [s["payer"], {"commitment": "confirmed"}]
            )["value"],
            "mint_supply_units": int.from_bytes(
                h.account(h.MINT)["raw"][36:44], "little"
            ),
            "payer_token_balance_units": h.token_balance(
                h.ata(Pubkey.from_string(s["payer"]))
            ),
            "entropy_samples_remaining": int.from_bytes(var[200:208], "little"),
            "checked_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
        h.save(OUT / "status.json", snapshot)
        print(json.dumps(snapshot, indent=2))


if __name__ == "__main__":
    main()
