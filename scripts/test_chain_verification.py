import base64
import hashlib
import unittest
import uuid
from chain_verification import token_delta, verify_settlement
from localnet import b58encode


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
        identity = str(uuid.UUID(int=7))
        owner, helper, mint = b58encode(bytes([1]) * 32), b58encode(bytes([2]) * 32), b58encode(bytes([3]) * 32)
        if same_wallet:
            helper = owner
        root = bytes([4]) * 32
        config = hashlib.sha256(b"account:Config").digest()[:8] + bytes(32) + bytes([3]) * 32 + bytes(33)
        data = bytearray(337)
        data[:8] = hashlib.sha256(b"account:Opportunity").digest()[:8]
        data[56:88] = bytes([1]) * 32
        data[120:152] = bytes([3]) * 32
        data[184:192] = (120_000_000).to_bytes(8, "little")
        data[193:195] = (5000).to_bytes(2, "little")
        data[246] = 1; data[247:279] = root; data[279] = 1
        data[280:288] = (60_000_000).to_bytes(8, "little"); data[288] = 1
        owner_ticket = {"opportunity": identity, "root": root.hex(), "amount": 60_000_000, "unlockReceipt": "signature"}
        guest_ticket = {"opportunity": identity, "root": root.hex(), "amount": 60_000_000, "wallet": helper, "index": 0}
        transactions = {"meta": {"err": None, "preTokenBalances": [], "postTokenBalances": [
            balance(owner, mint, 120_000_000)] if same_wallet else [balance(owner, mint, 60_000_000), balance(helper, mint, 60_000_000)]}}
        status = {"err": None, "confirmationStatus": "confirmed"}
        def rpc(method, params):
            if method == "getProgramAccounts":
                raw = config if params[1]["filters"][0]["dataSize"] == 105 else data
                return [{"pubkey": "vault", "account": {"data": [base64.b64encode(raw).decode(), "base64"]}}]
            if method == "getSignatureStatuses":
                return {"value": [status]}
            if method == "getTransaction":
                return transactions
            raise AssertionError(method)
        submissions = [{"operation": f"unlock:{identity}", "signature": "signature"}]
        return rpc, owner_ticket, guest_ticket, submissions, transactions, status

    def test_confirmed_receipt_alone_cannot_prove_the_exact_payout(self):
        rpc, owner, guest, submissions, transaction, status = self.fixture()
        result = verify_settlement(rpc, owner, guest, submissions)
        self.assertEqual(60_000_000, result["helper_amount"])
        transaction["meta"]["postTokenBalances"][1]["uiTokenAmount"]["amount"] = "59000000"
        with self.assertRaises(ValueError):
            verify_settlement(rpc, owner, guest, submissions)
        status["confirmationStatus"] = "processed"
        with self.assertRaises(ValueError):
            verify_settlement(rpc, owner, guest, submissions)

    def test_two_players_can_bind_the_same_wallet_without_misreporting_its_delta(self):
        rpc, owner, guest, submissions, _, _ = self.fixture(same_wallet=True)
        result = verify_settlement(rpc, owner, guest, submissions)
        self.assertEqual(120_000_000, result["owner_balance_delta"])
        self.assertEqual(60_000_000, result["owner_amount"])
        self.assertEqual(60_000_000, result["helper_amount"])


if __name__ == "__main__":
    unittest.main()
