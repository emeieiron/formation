import base64
import hashlib
import unittest
from unittest.mock import patch

from chain_verification import PROGRAM, b58encode
from verify_release_demo import verify


class ReleasedDuoReceiptTest(unittest.TestCase):
    def fixture(self):
        owner, guest, mint, claim_key, contest, entry = [b58encode(bytes([i]) * 32) for i in range(1, 7)]
        data = bytearray(256)
        data[:8] = hashlib.sha256(b"account:Entry").digest()[:8]
        data[8:40] = bytes([5]) * 32
        data[109:141] = bytes([1]) * 32
        data[141] = 1
        data[150:182] = bytes([7]) * 32
        data[182] = 1
        data[183:191] = (75_000_000).to_bytes(8, "little")
        data[191:199] = (225_000_000).to_bytes(8, "little")
        data[199] = 1

        def transaction(name, accounts, recipient, amount, signer):
            return {"transaction": {"message": {
                "instructions": [{"programId": PROGRAM, "accounts": accounts,
                                  "data": b58encode(hashlib.sha256(("global:" + name).encode()).digest()[:8])}],
                "accountKeys": [{"pubkey": signer, "signer": True}]}},
                "meta": {"err": None, "preTokenBalances": [], "postTokenBalances": [
                    {"owner": recipient, "mint": mint, "uiTokenAmount": {"amount": str(amount)}}]}}
        unlock = transaction("unlock", [owner, contest, "sgt", "sgt-account", entry, "vault", "owner-token", mint],
                             owner, 225_000_000, owner)
        claim = transaction("claim", [guest, claim_key, guest, contest, entry, "receipt", "vault", "guest-token", mint],
                            guest, 75_000_000, claim_key)
        journey = {"passed": True, "started_at": 100, "finished_at": 200,
                   "devices": {"host": {"recipient": owner}, "guest": {"recipient": guest}}}

        def rpc(method, params):
            if method == "getProgramAccounts":
                config = bytes(40) + bytes([3]) * 32
                return [{"account": {"data": [base64.b64encode(config).decode(), "base64"]}}]
            if method == "getSignaturesForAddress":
                return [{"signature": "unlock" if params[0] == owner else "claim", "err": None,
                         "blockTime": 150, "confirmationStatus": "finalized"}]
            if method == "getTransaction":
                return unlock if params[0] == "unlock" else claim
            if method == "getAccountInfo":
                return {"value": {"owner": PROGRAM, "data": [base64.b64encode(data).decode(), "base64"]}}
            raise AssertionError(method)
        return journey, rpc, claim, data

    def test_separate_unlock_and_claim_prove_the_exact_duo_payments(self):
        journey, rpc, _, _ = self.fixture()
        with patch("verify_release_demo.rpc", rpc):
            result = verify(journey)
        self.assertEqual(225_000_000, result["host"]["balance_delta"])
        self.assertEqual(75_000_000, result["guest"]["balance_delta"])
        self.assertNotEqual(result["guest"]["claim_key"], result["guest"]["recipient"])

    def test_claim_from_a_different_entry_cannot_validate_the_video(self):
        journey, rpc, claim, _ = self.fixture()
        claim["transaction"]["message"]["instructions"][0]["accounts"][4] = "different-entry"
        with patch("verify_release_demo.rpc", rpc), self.assertRaises(ValueError):
            verify(journey)

    def test_claim_requires_exact_token_delta_and_the_participants_signature(self):
        journey, rpc, claim, _ = self.fixture()
        claim["meta"]["postTokenBalances"][0]["uiTokenAmount"]["amount"] = "74000000"
        with patch("verify_release_demo.rpc", rpc), self.assertRaises(ValueError):
            verify(journey)
        claim["meta"]["postTokenBalances"][0]["uiTokenAmount"]["amount"] = "75000000"
        claim["transaction"]["message"]["accountKeys"] = []
        with patch("verify_release_demo.rpc", rpc), self.assertRaises(ValueError):
            verify(journey)


if __name__ == "__main__":
    unittest.main()
