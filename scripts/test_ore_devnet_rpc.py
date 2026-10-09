"""Run with the same solders/pycryptodome environment as ore_devnet.py."""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from solders.hash import Hash
from solders.keypair import Keypair
from solders.system_program import TransferParams, transfer

import ore_devnet_rpc as h


class SubmissionTest(unittest.TestCase):
    def test_lost_response_reuses_the_saved_signature_after_restart(self):
        payer = Keypair()
        ix = transfer(
            TransferParams(
                from_pubkey=payer.pubkey(), to_pubkey=Keypair().pubkey(), lamports=1
            )
        )
        calls = []
        landed = False

        def rpc(method, params=None):
            nonlocal landed
            calls.append(method)
            if method == "getLatestBlockhash":
                return {
                    "value": {
                        "blockhash": str(Hash.default()),
                        "lastValidBlockHeight": 100,
                    }
                }
            if method == "getSignatureStatuses":
                return {
                    "value": [
                        {"err": None, "confirmationStatus": "confirmed"}
                        if landed
                        else None
                    ]
                }
            if method == "getBlockHeight":
                return 1
            if method == "sendTransaction":
                landed = True
                raise TimeoutError("Response lost after landing")
            if method == "getTransaction":
                return {"slot": 2, "meta": {"fee": 5000}}
            raise AssertionError(method)

        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(h, "OUT", Path(directory)),
            patch.object(h, "rpc", rpc),
        ):
            with self.assertRaises(TimeoutError):
                h.send(payer, "r1-reset", [ix])
            saved = json.loads((Path(directory) / "pending/r1-reset.json").read_text())
            result = h.send(payer, "r1-reset", [ix])
            self.assertEqual(saved["signature"], result["signature"])
            self.assertEqual(1, calls.count("sendTransaction"))
            self.assertEqual(1, calls.count("getLatestBlockhash"))

    def test_expiry_rechecks_history_and_does_not_rebroadcast(self):
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(h, "OUT", Path(directory)),
        ):
            pending = Path(directory) / "pending/r1-reset.json"
            h.save(
                pending, {"signature": "receipt", "bytes": "unused", "valid_until": 2}
            )
            calls = []

            def rpc(method, params=None):
                calls.append(method)
                if method == "getSignatureStatuses":
                    return {"value": [None]}
                if method == "getBlockHeight":
                    return 3
                raise AssertionError(method)

            with (
                patch.object(h, "rpc", rpc),
                self.assertRaisesRegex(RuntimeError, "expired"),
            ):
                h.send(Keypair(), "r1-reset", [])
            self.assertEqual(2, calls.count("getSignatureStatuses"))
            self.assertFalse(pending.exists())

    def test_atomic_save_preserves_previous_receipt_when_replace_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "receipt.json"
            h.save(path, {"signature": "old"})
            with patch.object(Path, "replace", side_effect=OSError("Disk unavailable")):
                with self.assertRaises(OSError):
                    h.save(path, {"signature": "new"})
            self.assertEqual("old", json.loads(path.read_text())["signature"])


if __name__ == "__main__":
    unittest.main()
