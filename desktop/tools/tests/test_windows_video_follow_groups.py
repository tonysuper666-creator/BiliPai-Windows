"""Whole fixed owner generation and exact Windows group lifetime inverse."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parents[1]
sys.path.insert(0, str(TOOLS))
VM = "com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt"


def restore(body, rows):
    for row in reversed(rows):
        i = row["offset"]
        if body[i:i + len(row["after"])] != row["after"]:
            raise AssertionError("Inverse bytes changed")
        body = body[:i] + row["before"] + body[i + len(row["after"]):]
    return body


def restore_final_send_stage(body, row):
    assert hashlib.sha256(body.encode()).hexdigest() == row["sha256LF"]
    body = restore(body, row["sameSendExpectedSourceInverseEdits"])
    assert hashlib.sha256(body.encode()).hexdigest() == row["sameSendExpectedSourceBeforeSha256LF"]
    return body


class OriginalVideoFollowGroupGeneratorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("follow_group_owner_contract", TOOLS / "extract-upstream-video-full-owner.py")
        cls.owner = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = cls.owner
        spec.loader.exec_module(cls.owner)
        cls.private = tempfile.TemporaryDirectory(prefix="bp-follow-group-contract-")
        cls.output = Path(cls.private.name)
        with contextlib.redirect_stdout(io.StringIO()):
            cls.rows = cls.owner.generate(REPO, cls.output / "actual", True)
            with mock.patch.object(cls.owner, "follow_group_source_lifetime_delta", lambda path, body, audit: body):
                cls.baseline_rows = cls.owner.generate(REPO, cls.output / "baseline", True)
        cls.row = next(row for row in cls.rows if row["path"] == VM)
        cls.baseline_row = next(row for row in cls.baseline_rows if row["path"] == VM)
        cls.actual = (cls.output / "actual" / VM).read_text(encoding="utf-8")
        cls.baseline = (cls.output / "baseline" / VM).read_text(encoding="utf-8")
        cls.audit = json.loads((cls.output / "actual/original-video-follow-group-lifetime.json").read_text(encoding="utf-8"))

    @classmethod
    def tearDownClass(cls):
        cls.private.cleanup()

    def test_actual_sole_producer_consumes_delta_and_all_three_edits_restore_whole_stage(self):
        self.assertEqual(3, len(self.row["followGroupInverseEdits"]))
        body = restore(restore_final_send_stage(self.actual, self.row), self.row["failureRecoveryInverseEdits"])
        baseline = restore(restore_final_send_stage(self.baseline, self.baseline_row), self.baseline_row["failureRecoveryInverseEdits"])
        self.assertEqual(baseline, restore(body, self.row["followGroupInverseEdits"]))
        self.assertEqual(1, len(self.audit["audits"]))
        audit = self.audit["audits"][0]
        self.assertEqual(VM, audit["path"])
        self.assertEqual(self.row["followGroupInverseEdits"], audit["inverseEdits"])
        self.assertEqual(hashlib.sha256(baseline.encode()).hexdigest(), audit["beforeSha256LF"])
        self.assertEqual(hashlib.sha256(body.encode()).hexdigest(), audit["afterSha256LF"])
        self.assertEqual("79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40", self.audit["upstreamCommit"])

    def test_original_business_protocol_and_ui_are_preserved_only_vm_lifetime_changes(self):
        actual = {row["path"]: row for row in self.rows}
        before = {row["path"]: row for row in self.baseline_rows}
        for path, row in before.items():
            if path in (VM, "original-video-follow-group-lifetime.json"):
                continue
            self.assertEqual(row["sha256LF"], actual[path]["sha256LF"], path)
        lifetime = self.row["followGroupInverseEdits"][-1]["after"]
        self.assertEqual(1, lifetime.count("environment.actions.getFollowGroupTags()"))
        self.assertEqual(1, lifetime.count("environment.actions.getUserFollowGroupIds(targetMid)"))
        self.assertEqual(1, lifetime.count("environment.actions.overwriteFollowGroupIds("))
        self.assertEqual(3, lifetime.count("as? CancellationException"))
        self.assertIn("val selected = _followGroupSelectedTagIds.value.toSet()", lifetime)
        self.assertIn("val targetMid = request.targetMid", lifetime)
        self.assertIn("_toastEvent.receiveAsFlow().map { Triple(null, it, false) }", self.actual)
        self.assertLess(self.actual.index("val toastEvent = kotlinx.coroutines.flow.merge"),
                        self.actual.index("if (request == null || desktopFollowGroupFeedbackCurrent"))
        self.assertIn("request.finish(ticket) { _isSavingFollowGroups.value = false }", lifetime)

    def test_wrong_original_raw_and_corrupted_inverse_are_rejected(self):
        recipe = next(row for row in self.owner.RECIPES if row["output"] == VM)
        original_source = self.owner._desktop_canonical_source
        with tempfile.TemporaryDirectory(prefix="bp-group-invalid-") as root:
            changed = Path(root) / "changed.kt"
            raw = Path(original_source(REPO, recipe["originalPath"])).read_bytes()
            changed.write_bytes(raw + b"// changed\n")
            def source(repo, path):
                return changed if path == recipe["originalPath"] else original_source(repo, path)
            with mock.patch.object(self.owner, "_desktop_canonical_source", source), contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(AssertionError):
                    self.owner.generate(REPO, Path(root) / "output", True)
        stage = restore(restore_final_send_stage(self.actual, self.row), self.row["failureRecoveryInverseEdits"])
        rows = self.row["followGroupInverseEdits"]
        changed = stage[:rows[-1]["offset"]] + "!" + stage[rows[-1]["offset"] + 1:]
        with self.assertRaises(AssertionError):
            restore(changed, rows)


if __name__ == "__main__":
    unittest.main()
