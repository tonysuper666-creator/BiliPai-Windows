"""Check original v041 personal badge blocks through the existing Windows producers."""
from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))
import v041_video_status_personal_consumers as adapter
from v025_source_paths import canonical_source


def load(name):
    spec = importlib.util.spec_from_file_location("personal_status_" + name.replace("-", "_"), TOOLS / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class OriginalPersonalStatusConsumersTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bilipai-personal-status-")
        cls.root = Path(cls.temp.name)
        cls.outputs = {}
        for key, tool in [
            ("favorites", "extract-upstream-favorites"),
            ("profile", "extract-upstream-profile-main"),
        ]:
            output = cls.root / key
            load(tool).generate(REPO, output)
            cls.outputs[key] = output

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_fixed_raw_sources_and_each_badge_block_match_exact_upstream(self):
        sources = adapter.fixed(REPO)
        self.assertEqual(3, len(sources))
        self.assertEqual("d18fb20e31adb11bbec9fae6dfce7d705d556217", adapter.COMMIT)
        badge_blocks = 0
        for row in adapter.PINS:
            raw = sources[row["path"]].encode()
            self.assertEqual(row["sha256"], hashlib.sha256(raw).hexdigest())
            self.assertEqual(row["gitBlob"], hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest())
            for edit in adapter.PATCHES[row["path"]]:
                self.assertIn(edit["upstreamBlock"], sources[row["path"]])
                badge_blocks += int("coverOverlayContent = {" in edit["upstreamBlock"])
        self.assertEqual(4, badge_blocks)

    def test_all_three_whole_canonical_sources_restore_after_the_four_badge_insertions(self):
        output = self.root / "whole-inverse"
        for row in adapter.PINS:
            with self.subTest(path=row["path"]):
                original = canonical_source(REPO, row["path"]).read_bytes().replace(b"\r\n", b"\n").decode()
                generated = adapter.apply(REPO, row["path"], original, output)
                proof = json.loads((output / "v041-personal-video-status-proofs" / (row["filename"] + ".json")).read_bytes())
                self.assertTrue(proof["exactCompleteInverse"])
                self.assertEqual(hashlib.sha256(generated.encode()).hexdigest(), proof["afterSha256LF"])
                for edit in reversed(proof["inverseEdits"]):
                    offset = edit["offset"]
                    self.assertEqual(edit["after"], generated[offset:offset + len(edit["after"])])
                    generated = generated[:offset] + edit["before"] + generated[offset + len(edit["after"]):]
                self.assertEqual(original, generated)
                self.assertEqual(hashlib.sha256(generated.encode()).hexdigest(), proof["beforeSha256LF"])

    def test_actual_favorites_history_and_profile_producers_emit_status_badges_once(self):
        for row in adapter.PINS:
            with self.subTest(path=row["path"]):
                key = "profile" if row["filename"] == "ProfileScreen.kt" else "favorites"
                matches = list((self.outputs[key] / "com").rglob(row["filename"]))
                self.assertEqual(1, len(matches))
                body = matches[0].read_text(encoding="utf-8")
                self.assertEqual(2 if row["filename"] == "HistoryPersonalCard.kt" else 1,
                                 body.count("labels = video.statusLabels,") + body.count("labels = item.statusLabels,"))
                for edit in adapter.PATCHES[row["path"]]:
                    self.assertIn(edit["upstreamBlock"], body)
                self.assertTrue((self.outputs[key] / "v041-personal-video-status-proofs" / (row["filename"] + ".json")).is_file())

    def test_changed_canonical_source_is_rejected(self):
        row = adapter.PINS[0]
        body = canonical_source(REPO, row["path"]).read_text(encoding="utf-8-sig") + "\n"
        with tempfile.TemporaryDirectory(prefix="bilipai-personal-anchor-") as directory:
            with self.assertRaisesRegex(ValueError, "canonical source changed"):
                adapter.apply(REPO, row["path"], body, Path(directory))
            self.assertEqual([], list(Path(directory).rglob("*")))

    def test_changed_raw_profile_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="bilipai-personal-raw-") as directory:
            root = Path(directory)
            shutil.copytree(REPO / adapter.ARCHIVE, root / adapter.ARCHIVE)
            path = root / adapter.ARCHIVE / "ProfileScreen.kt"
            path.write_bytes(path.read_bytes() + b" ")
            with self.assertRaisesRegex(ValueError, "source identity changed"):
                adapter.fixed(root)


if __name__ == "__main__":
    unittest.main()

