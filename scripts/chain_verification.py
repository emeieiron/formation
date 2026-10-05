"""Check the configured mint and confirmed vault settlement; never read signing material."""
import base64
import hashlib
import json
import os
from localnet import b58encode

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROGRAM = json.load(open(os.path.join(ROOT, "program", "formation-vault", "idl", "formation_vault.json")))["address"]

# Entry: contest, SGT mint, round, index, rent payer, holder at unlock, state, budget, roster root and size,
# guest share, owner's payment, claimed bits.
ENTRY = {"contest": (8, 32), "owner": (109, 32), "state": (141, 1), "root": (150, 32), "size": (182, 1),
         "guest_share": (183, 8), "owner_paid": (191, 8), "claimed": (199, 8)}


def field(data, name):
    at, size = ENTRY[name]
    return data[at:at + size]


def number(data, name):
    return int.from_bytes(field(data, name), "little")


def configured_mint(rpc):
    discriminator = hashlib.sha256(b"account:Config").digest()[:8]
    accounts = rpc("getProgramAccounts", [PROGRAM, {"encoding": "base64", "commitment": "confirmed", "filters": [
        {"memcmp": {"offset": 0, "bytes": b58encode(discriminator)}}]}])
    if len(accounts) != 1:
        raise ValueError("The chain must have exactly one Formation vault config")
    return b58encode(base64.b64decode(accounts[0]["account"]["data"][0])[40:72])


def mint_balance(rpc, owner, mint):
    result = rpc("getTokenAccountsByOwner", [owner, {"mint": mint}, {"encoding": "jsonParsed", "commitment": "confirmed"}])
    return sum(int(account["account"]["data"]["parsed"]["info"]["tokenAmount"]["amount"]) for account in result["value"])


def token_delta(transaction, owner, mint):
    meta = transaction["meta"]
    if meta["err"] is not None:
        raise ValueError("The settlement transaction failed")
    def total(field_name):
        return sum(int(row["uiTokenAmount"]["amount"]) for row in meta.get(field_name, [])
                   if row.get("owner") == owner and row["mint"] == mint)
    return total("postTokenBalances") - total("preTokenBalances")


def verify_settlement(rpc, owner_ticket, guest_ticket, submissions):
    identity = owner_ticket["opportunity"]
    mint = configured_mint(rpc)
    found = rpc("getAccountInfo", [identity, {"encoding": "base64", "commitment": "confirmed"}])["value"]
    if found is None or found["owner"] != PROGRAM:
        raise ValueError("The settled entry was not found")
    data = base64.b64decode(found["data"][0])
    if data[:8] != hashlib.sha256(b"account:Entry").digest()[:8] or field(data, "state")[0] != 1:
        raise ValueError("The chain entry is not unlocked")
    if field(data, "root").hex() != owner_ticket["root"] or b58encode(field(data, "contest")) != owner_ticket["contest"]:
        raise ValueError("The chain contest or committed roster differs from the saved result")
    if guest_ticket["opportunity"] != identity or guest_ticket["root"] != owner_ticket["root"]:
        raise ValueError("Both phones must retain the same settled roster")
    owner_amount = number(data, "owner_paid")
    helper_amount = number(data, "guest_share")
    if int(owner_ticket["amount"]) != owner_amount or int(guest_ticket["amount"]) != helper_amount:
        raise ValueError("Saved payout amounts differ from the vault")
    relevant = [item for item in submissions if item["operation"] == f"unlock:{identity}" or item["operation"].startswith(f"payout:{identity}:")]
    signatures = list(dict.fromkeys(item["signature"] for item in relevant))
    if not signatures or owner_ticket["unlockReceipt"] not in signatures:
        raise ValueError("The app did not retain the real unlock transaction")
    transactions = []
    for signature in signatures:
        status = rpc("getSignatureStatuses", [[signature], {"searchTransactionHistory": True}])["value"][0]
        if status is None or status["err"] is not None or status["confirmationStatus"] not in ("confirmed", "finalized"):
            raise ValueError("A settlement signature is not confirmed")
        transaction = rpc("getTransaction", [signature, {"encoding": "jsonParsed", "commitment": "confirmed", "maxSupportedTransactionVersion": 0}])
        if transaction is None:
            raise ValueError("The confirmed transaction is unavailable")
        transactions.append(transaction)
    owner = b58encode(field(data, "owner"))
    recipient = guest_ticket.get("wallet")
    owner_delta = sum(token_delta(tx, owner, mint) for tx in transactions)
    expected_owner = owner_amount + (helper_amount if recipient == owner else 0)
    if owner_delta != expected_owner:
        raise ValueError(f"Owner received {owner_delta} base units, expected {expected_owner}")
    helper_delta = sum(token_delta(tx, recipient, mint) for tx in transactions) if recipient else 0
    if recipient:
        claimed = number(data, "claimed") & (1 << guest_ticket["index"])
        expected_helper = helper_amount + (owner_amount if recipient == owner else 0)
        if not claimed or helper_delta != expected_helper:
            raise ValueError("The bound helper's exact payout is not confirmed")
    return {"opportunity": identity, "contest": owner_ticket["contest"], "mint": mint,
            "signatures": signatures, "owner": owner, "owner_amount": owner_amount, "owner_balance_delta": owner_delta,
            "helper": recipient, "helper_amount": helper_amount if recipient else 0,
            "helper_balance_delta": helper_delta, "helper_reserved": helper_amount if not recipient else 0}
