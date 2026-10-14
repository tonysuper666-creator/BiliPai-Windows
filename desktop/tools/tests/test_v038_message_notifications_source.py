import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("v038_notifications", REPO / "desktop/tools/v038_message_notifications.py")
source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source)


class V038NotificationSourceTest(unittest.TestCase):
    def test_complete_original_bodies_are_recovered_after_platform_adaptation(self):
        originals, _ = source.fixed_sources(REPO)
        for name, fn in [("MessageNotificationPolicy.kt", source.pure_policy),
                         ("MessageNotificationChecker.kt", source.notification_poller),
                         ("MessageNotificationStateStore.kt", source.state_store),
                         ("DynamicPersonalFeedApi.kt", source.personal_feed)]:
            with self.subTest(name=name):
                body, proof = fn(REPO)
                for row in reversed(proof["edits"]):
                    at = row["offset"]
                    self.assertEqual(row["after"], body[at:at+len(row["after"])])
                    body = body[:at]+row["before"]+body[at+len(row["after"]):]
                self.assertEqual(originals[name], body)
                self.assertTrue(proof["completeOriginalInverse"])

    def test_old_id_based_polling_is_absent_from_the_full_new_poller(self):
        body, _ = source.notification_poller(REPO)
        self.assertIn("source.feedUnread()", body)
        self.assertIn("source.personalDynamicFeed", body)
        for old in ("source.replyIds()", "source.atIds()", "source.likeIds()", "source.dynamicFeed("):
            self.assertNotIn(old, body)
        self.assertNotIn("NetworkModule", body)
        self.assertIn("pendingPrivateSessionKeys", body)

    def test_preload_wire_flags_and_all_original_regressions_are_preserved(self):
        body, proof = source.personal_feed(REPO)
        self.assertIn("ProtoWire.int32(4, 1)", body)
        self.assertNotIn("private const val", body)
        self.assertIn("readOffset", body)
        tests = source.original_tests(REPO)
        self.assertEqual(42, sum(text.count("@Test") for text, _ in tests.values()))
        self.assertTrue(all(p["completeOriginalInverse"] for _, p in tests.values()))

    def test_changed_notification_baseline_or_read_policy_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp); root = repo/source.ARCHIVE
            shutil.copytree(REPO/source.ARCHIVE, root)
            path = root/"DynamicPersonalFeedApi.kt"
            path.write_bytes(path.read_bytes().replace(b"ProtoWire.int32(4, 1)", b"ProtoWire.int32(4, 0)"))
            with self.assertRaisesRegex(ValueError, "source bytes changed"):
                source.personal_feed(repo)

    def test_actual_grpc_tests_use_current_framework_and_recover_the_complete_original(self):
        originals, _ = source.fixed_sources(REPO)
        for name, (body, proof) in source.original_tests(REPO).items():
            with self.subTest(name=name):
                self.assertNotIn("import org.junit.", body)
                self.assertIn("import kotlin.test.Test", body)
                self.assertEqual(originals[name].count("@Test"), body.count("@Test"))
                # Only framework imports are changed; method bodies and every
                # original assertion remain part of the complete inverse.
                for edit in reversed(proof["edits"]):
                    self.assertTrue(edit["before"].startswith("import "))
                    i = edit["offset"]
                    self.assertEqual(edit["after"], body[i:i+len(edit["after"])])
                    body = body[:i]+edit["before"]+body[i+len(edit["after"]):]
                self.assertEqual(originals[name], body)

    def test_manifest_relabeling_cannot_silently_replace_the_fixed_commit(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp); root = repo/source.ARCHIVE
            shutil.copytree(REPO/source.ARCHIVE, root)
            path = root/"manifest.json"; data = json.loads(path.read_bytes()); data["fixedUpstreamCommit"]="0"*40
            path.write_text(json.dumps(data),encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "manifest changed"):
                source.fixed_sources(repo)

    def test_existing_notification_producer_emits_complete_new_bodies_and_original_tests_once(self):
        import sys
        sys.path.insert(0, str(REPO / "desktop/tools"))
        spec = importlib.util.spec_from_file_location("notification_sole_producer", REPO / "desktop/tools/extract-upstream-message-notifications.py")
        producer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(producer)
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)
            files = producer.generate(REPO, output)
            self.assertEqual(5, len(files))
            self.assertEqual(5, len(set(files)))
            self.assertEqual(42, sum(p.read_text(encoding="utf-8").count("@Test") for p in (output/"policy-tests").rglob("*.kt")))
            emitted = output/"com/android/purebilibili/feature/message/notification/MessageNotificationChecker.kt"
            self.assertIn("source.feedUnread()", emitted.read_text(encoding="utf-8"))
            self.assertNotIn("NetworkModule", emitted.read_text(encoding="utf-8"))
        entries = producer.inventory(REPO)
        self.assertTrue(all(row["mode"] == "policy-extract" for row in entries))

    def test_current_manifest_retires_old_direct_policy_but_preserves_its_canonical_source_identity(self):
        manifest = json.loads((REPO/"desktop/upstream-sources.json").read_bytes())
        rows = [r for r in manifest["sources"] if r["path"] == "app/src/main/java/com/android/purebilibili/feature/message/notification/MessageNotificationPolicy.kt"]
        self.assertEqual(1, len(rows))
        self.assertEqual("policy-extract", rows[0]["mode"])
        self.assertEqual("b11892a23659db840095df6ed5d73593bb47238c8f58586236c49cc10ad59f39", rows[0]["sha256"])


if __name__ == "__main__":
    unittest.main()
