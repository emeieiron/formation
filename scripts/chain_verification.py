"""Check the configured mint and confirmed vault settlement; never read signing material."""
import base64
import hashlib
import uuid
from localnet import b58encode

PROGRAM = "3AzZbKhGFcnaBRRenDDdNSdVumjKoXSkNHsPVeo5q6GW"


def configured_mint(rpc):
    discriminator = hashlib.sha256(b"account:Config").digest()[:8]
    accounts = rpc("getProgramAccounts", [PROGRAM, {"encoding": "base64", "commitment": "confirmed", "filters": [
        {"dataSize": 105}, {"memcmp": {"offset": 0, "bytes": b58encode(discriminator)}}]}])
    if len(accounts) != 1:
        raise ValueError("The chain must have exactly one Formation vault config")
    data = base64.b64decode(accounts[0]["account"]["data"][0])
    if data[:8] != discriminator:
        raise ValueError("The vault config discriminator differs")
    return b58encode(data[40:72])


def mint_balance(rpc, owner, mint):
    result = rpc("getTokenAccountsByOwner", [owner, {"mint": mint}, {"encoding": "jsonParsed", "commitment": "confirmed"}])
    return sum(int(account["account"]["data"]["parsed"]["info"]["tokenAmount"]["amount"]) for account in result["value"])


def token_delta(transaction, owner, mint):
    meta = transaction["meta"]
    if meta["err"] is not None:
        raise ValueError("The settlement transaction failed")
    def total(field):
        return sum(int(row["uiTokenAmount"]["amount"]) for row in meta.get(field, [])
                   if row.get("owner") == owner and row["mint"] == mint)
    return total("postTokenBalances") - total("preTokenBalances")


def verify_settlement(rpc, owner_ticket, guest_ticket, submissions):
    identity = owner_ticket["opportunity"]
    mint = configured_mint(rpc)
    accounts = rpc("getProgramAccounts", [PROGRAM, {"encoding": "base64", "commitment": "confirmed", "filters": [
        {"dataSize": 337}, {"memcmp": {"offset": 8, "bytes": b58encode(uuid.UUID(identity).bytes)}}]}])
    if len(accounts) != 1:
        raise ValueError("The settled reward account was not found")
    data = base64.b64decode(accounts[0]["account"]["data"][0])
    if data[:8] != hashlib.sha256(b"account:Opportunity").digest()[:8] or data[246] != 1:
        raise ValueError("The chain reward is not unlocked")
    if b58encode(data[120:152]) != mint or data[247:279].hex() != owner_ticket["root"]:
        raise ValueError("The chain mint or committed roster differs from the saved result")
    if guest_ticket["opportunity"] != identity or guest_ticket["root"] != owner_ticket["root"]:
        raise ValueError("Both phones must retain the same settled roster")
    amount = int.from_bytes(data[184:192], "little")
    owner_amount = amount * int.from_bytes(data[193:195], "little") // 10_000
    helper_amount = int.from_bytes(data[280:288], "little")
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
    owner = b58encode(data[56:88])
    recipient = guest_ticket.get("wallet")
    owner_delta = sum(token_delta(tx, owner, mint) for tx in transactions)
    expected_owner = owner_amount + (helper_amount if recipient == owner else 0)
    if owner_delta != expected_owner:
        raise ValueError(f"Owner received {owner_delta} base units, expected {expected_owner}")
    helper_delta = sum(token_delta(tx, recipient, mint) for tx in transactions) if recipient else 0
    if recipient:
        claimed = int.from_bytes(data[288:296], "little") & (1 << guest_ticket["index"])
        expected_helper = helper_amount + (owner_amount if recipient == owner else 0)
        if not claimed or helper_delta != expected_helper:
            raise ValueError("The bound helper's exact payout is not confirmed")
    return {"opportunity": identity, "vault": accounts[0]["pubkey"], "mint": mint,
            "signatures": signatures, "owner": owner, "owner_amount": owner_amount, "owner_balance_delta": owner_delta,
            "helper": recipient, "helper_amount": helper_amount if recipient else 0,
            "helper_balance_delta": helper_delta, "helper_reserved": helper_amount if not recipient else 0}
