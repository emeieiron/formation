import base64
import hashlib
import unittest
from chain_verification import PROGRAM, b58encode, token_delta, verify_settlement


def balance(owner, mint, amount):
    return {"owner": owner, "mint": mint, "uiTokenAmount": {"amount": str(amount)}}


class ChainVerificationTest(unittest.TestCase):
    def test_token_delta_ignores_other_mints_and_supports_new_accounts(self):
        transaction = {"meta": {"err": None, "preTokenBalances": [], "postTokenBalances": [
            balance("owner", "mint", 60_000_000), balance("owner", "other", 900_000_000)]}}
        self.assertEqual(60_000_000, token_delta(transaction, "owner", "mint"))
        transaction["meta"]["err"] = {"InstructionError": [0, "Custom"]}
        with self.assertRaises(ValueError):
            token_delta(transaction, "owner", "mint")

    def fixture(self, same_wallet=False):
        entry = b58encode(bytes([7]) * 32)
        contest = b58encode(bytes([5]) * 32)
        owner, helper, mint = b58encode(bytes([1]) * 32), b58encode(bytes([2]) * 32), b58encode(bytes([3]) * 32)
        if same_wallet:
            helper = owner
        root = bytes([4]) * 32
        config = hashlib.sha256(b"account:Config").digest()[:8] + bytes(32) + bytes([3]) * 32 + bytes(32)
        # Entry: contest 8, owner 109, state 141, root 150, size 182, guest share 183, owner paid 191, claimed 199.
        data = bytearray(256)
        data[:8] = hashlib.sha256(b"account:Entry").digest()[:8]
        data[8:40] = bytes([5]) * 32
        data[109:141] = bytes([1]) * 32
        data[141] = 1
        data[150:182] = root
        data[182] = 1
        data[183:191] = (30_000_000).to_bytes(8, "little")
        data[191:199] = (90_000_000).to_bytes(8, "little")
        data[199] = 1
        owner_ticket = {"opportunity": entry, "contest": contest, "root": root.hex(), "amount": 90_000_000, "unlockReceipt": "signature"}
        guest_ticket = {"opportunity": entry, "root": root.hex(), "amount": 30_000_000, "wallet": helper, "index": 0}
        transactions = {"meta": {"err": None, "preTokenBalances": [], "postTokenBalances": [
            balance(owner, mint, 120_000_000)] if same_wallet else [balance(owner, mint, 90_000_000), balance(helper, mint, 30_000_000)]}}
        status = {"err": None, "confirmationStatus": "confirmed"}
        def rpc(method, params):
            if method == "getProgramAccounts":
                return [{"pubkey": "config", "account": {"data": [base64.b64encode(config).decode(), "base64"]}}]
            if method == "getAccountInfo":
                return {"value": {"owner": PROGRAM, "data": [base64.b64encode(bytes(data)).decode(), "base64"]}}
            if method == "getSignatureStatuses":
                return {"value": [status]}
            if method == "getTransaction":
                return transactions
            raise AssertionError(method)
        submissions = [{"operation": f"unlock:{entry}", "signature": "signature"}]
        return rpc, owner_ticket, guest_ticket, submissions, transactions, status

    def test_confirmed_receipt_alone_cannot_prove_the_exact_payout(self):
        rpc, owner, guest, submissions, transaction, status = self.fixture()
        result = verify_settlement(rpc, owner, guest, submissions)
        self.assertEqual(30_000_000, result["helper_amount"])
        transaction["meta"]["postTokenBalances"][1]["uiTokenAmount"]["amount"] = "29000000"
        with self.assertRaises(ValueError):
            verify_settlement(rpc, owner, guest, submissions)
        status["confirmationStatus"] = "processed"
        with self.assertRaises(ValueError):
            verify_settlement(rpc, owner, guest, submissions)

    def test_two_players_can_bind_the_same_wallet_without_misreporting_its_delta(self):
        rpc, owner, guest, submissions, _, _ = self.fixture(same_wallet=True)
        result = verify_settlement(rpc, owner, guest, submissions)
        self.assertEqual(120_000_000, result["owner_balance_delta"])
        self.assertEqual(90_000_000, result["owner_amount"])
        self.assertEqual(30_000_000, result["helper_amount"])


if __name__ == "__main__":
    unittest.main()
