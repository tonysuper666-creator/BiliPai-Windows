"""Final output receipts retain the earlier stage's verified full inverse."""
import hashlib
import unittest

from v029_home_load import Delta, finish_audit


class HomeAuditCompletionTest(unittest.TestCase):
    def receipt(self):
        stage = Delta("标题\noriginal\n尾巴\n")
        stage.change("original", "first stage")
        audits = []
        return stage.done(audits, "example.kt"), audits[0]

    def test_final_inverse_keeps_prior_edits_and_unicode_offsets(self):
        stage, receipt = self.receipt()
        final = "prefix\n" + stage.replace("first stage", "last stage").removesuffix("尾巴\n")
        completed = finish_audit(receipt, stage, final)
        self.assertEqual(receipt["edits"], completed["edits"][:len(receipt["edits"])])
        self.assertEqual(hashlib.sha256(final.encode()).hexdigest(), completed["afterSha256LF"])
        for edit in reversed(completed["edits"]):
            at = edit["offset"]
            self.assertEqual(final[at:at + len(edit["after"])], edit["after"])
            final = final[:at] + edit["before"] + final[at + len(edit["after"]):]
        self.assertEqual("标题\noriginal\n尾巴\n", final)

    def test_stale_stage_and_tampered_inverse_are_rejected(self):
        stage, receipt = self.receipt()
        with self.assertRaisesRegex(ValueError, "stage input"):
            finish_audit(receipt, stage + "changed", stage)
        with self.assertRaisesRegex(ValueError, "stage inverse"):
            finish_audit({**receipt, "edits": [{"offset": 0, "before": "x", "after": "wrong"}]}, stage, stage)
        with self.assertRaisesRegex(ValueError, "original differs"):
            finish_audit({**receipt, "beforeSha256LF": "0" * 64}, stage, stage)
