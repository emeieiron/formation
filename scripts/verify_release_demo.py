#!/usr/bin/env python3
"""Verify a UI-only duo's unlock and later claim against confirmed devnet transactions."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import time
import urllib.error
import urllib.request

from chain_verification import ALPHABET, PROGRAM, b58encode, configured_mint, field, number, token_delta


def b58decode(text):
    value = 0
    for character in text.encode():
        value = value * 58 + ALPHABET.index(character)
    return bytes(len(text) - len(text.lstrip("1"))) + value.to_bytes((value.bit_length() + 7) // 8, "big")


def rpc(method, params):
    request = urllib.request.Request("https://api.devnet.solana.com", json.dumps(
        {"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode(),
        {"Content-Type": "application/json"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                reply = json.load(response)
            if "error" in reply:
                raise ValueError(reply["error"].get("message", "RPC failed"))
            return reply["result"]
        except urllib.error.HTTPError as error:
            if error.code != 429 or attempt == 3:
                raise
            time.sleep(2 ** attempt)


def instructions(transaction, name):
    discriminator = hashlib.sha256(("global:" + name).encode()).digest()[:8]
    return [ix for ix in transaction["transaction"]["message"]["instructions"]
            if ix.get("programId") == PROGRAM and ix.get("data")
            and b58decode(ix["data"])[:8] == discriminator]


def confirmed_transactions(address, journey):
    for receipt in rpc("getSignaturesForAddress", [address, {"limit": 20}]):
        if not receipt.get("blockTime") or not journey["started_at"] <= receipt["blockTime"] <= journey["finished_at"]:
            continue
        if receipt["err"] is not None or receipt["confirmationStatus"] not in ("confirmed", "finalized"):
            continue
        transaction = rpc("getTransaction", [receipt["signature"], {
            "encoding": "jsonParsed", "commitment": "confirmed", "maxSupportedTransactionVersion": 0}])
        if transaction is not None and transaction["meta"]["err"] is None:
            yield receipt, transaction


def verify(journey):
    if not journey["passed"]:
        raise ValueError("The complete UI journey must pass before checking settlement")
    owner, guest = (journey["devices"][role]["recipient"] for role in ("host", "guest"))
    mint = configured_mint(rpc)
    unlocks = []
    for receipt, tx in confirmed_transactions(owner, journey):
        for ix in instructions(tx, "unlock"):
            if ix["accounts"][0] == owner and ix["accounts"][7] == mint:
                unlocks.append((receipt, tx, ix))
    if len(unlocks) != 1:
        raise ValueError("Expected exactly one confirmed host unlock from this journey")
    unlock_receipt, unlock_tx, unlock_ix = unlocks[0]
    entry = unlock_ix["accounts"][4]
    claims = []
    for receipt, tx in confirmed_transactions(guest, journey):
        for ix in instructions(tx, "claim"):
            if ix["accounts"][2] == guest and ix["accounts"][4] == entry and ix["accounts"][8] == mint:
                claims.append((receipt, tx, ix))
    if len(claims) != 1:
        raise ValueError("Expected exactly one confirmed guest claim against the host's unlocked entry")
    claim_receipt, claim_tx, claim_ix = claims[0]
    account = rpc("getAccountInfo", [entry, {"encoding": "base64", "commitment": "confirmed"}])["value"]
    if account is None or account["owner"] != PROGRAM:
        raise ValueError("The settlement entry is not owned by the Formation vault")
    data = base64.b64decode(account["data"][0])
    if data[:8] != hashlib.sha256(b"account:Entry").digest()[:8] or number(data, "state") != 1:
        raise ValueError("The Formation entry is not unlocked")
    if b58encode(field(data, "owner")) != owner or number(data, "size") != 1 or number(data, "claimed") != 1:
        raise ValueError("The duo's owner or claimed guest slot differs from the recorded participants")
    owner_amount, guest_amount = number(data, "owner_paid"), number(data, "guest_share")
    if (owner_amount, guest_amount) != (225_000_000, 75_000_000):
        raise ValueError("The entry does not match the displayed 225/75 token split")
    if token_delta(unlock_tx, owner, mint) != owner_amount or token_delta(claim_tx, guest, mint) != guest_amount:
        raise ValueError("The recipients did not receive the exact amounts committed by the vault")
    claim_key = claim_ix["accounts"][1]
    signers = {key["pubkey"] for key in claim_tx["transaction"]["message"]["accountKeys"] if key["signer"]}
    if claim_key not in signers or claim_key == guest:
        raise ValueError("The later claim must be signed by the participant's separate claim key")
    return {"network": "devnet", "program": PROGRAM, "mint": mint, "entry": entry,
            "roster_root": field(data, "root").hex(), "claimed_guest_slots": number(data, "claimed"),
            "host": {"recipient": owner, "signature": unlock_receipt["signature"],
                     "status": unlock_receipt["confirmationStatus"], "balance_delta": owner_amount},
            "guest": {"recipient": guest, "claim_key": claim_key, "signature": claim_receipt["signature"],
                      "status": claim_receipt["confirmationStatus"], "balance_delta": guest_amount}}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("journey", type=Path)
    args = parser.parse_args()
    evidence = verify(json.loads(args.journey.read_text()))
    output = args.journey.with_name("chain-confirmation.json")
    output.write_text(json.dumps(evidence, indent=2) + "\n")
    print(json.dumps(evidence, indent=2))
