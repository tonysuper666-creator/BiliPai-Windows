"""Exercise the actual producer receipt; behavior is tested by the Compose consumer.

Gradle materializes the pinned legacy input tree at .legacy-root. Run from the
checkout with: python3 -m unittest desktop.tools.tests.test_bangumi_review_queries -v
"""
from pathlib import Path
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
PRODUCER = REPO / "desktop/tools/extract-upstream-bangumi-pages.py"
ORIGINAL = "app/src/main/java/com/android/purebilibili/feature/bangumi/BangumiReviewScreen.kt"


def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


class BangumiReviewProducerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = REPO if REPO.name == ".legacy-root" else REPO / ".legacy-root"
        if not (cls.source / ORIGINAL).is_file():
            raise RuntimeError("Materialize the pinned .legacy-root using the desktop Gradle settings first")
        build = REPO / "desktop/build"
        build.mkdir(exist_ok=True)
        cls.directory = tempfile.TemporaryDirectory(prefix="review-producer-test-", dir=build)
        cls.addClassCleanup(cls.directory.cleanup)
        cls.output = Path(cls.directory.name) / "first"
        cls.run_producer(cls.output)
        cls.receipt = json.loads((cls.output / "bangumi-pages-producer-inventory.json").read_text())

    @classmethod
    def run_producer(cls, output):
        subprocess.run([sys.executable, str(PRODUCER), "--repo", str(cls.source), "--output", str(output)],
                       cwd=REPO, check=True, capture_output=True, text=True)

    def test_review_screen_and_request_receipts_reverse_with_exact_counts_and_hashes(self):
        # Hub/detail rows describe intermediate stages replaced by v0.4.6. The
        # review screen and request adapter retain these complete original bodies.
        sources = {ORIGINAL, "app/src/main/java/com/android/purebilibili/data/repository/BangumiReviewRepository.kt"}
        rows = [row for row in self.receipt["emitted"] if row["originalPath"] in sources]
        self.assertEqual(sources, {row["originalPath"] for row in rows})
        for row in rows:
            with self.subTest(source=row["originalPath"]):
                self.assertTrue(row["completeOriginalFileBody"])
                self.assertTrue(row["inverseByteEqual"])
                body = (self.output / row["output"]).read_text(encoding="utf-8").split("\n", 1)[1]
                self.assertEqual(row["adaptedSha256LF"], sha(body))
                for edit in reversed(row["changes"]):
                    self.assertEqual(edit["count"], body.count(edit["after"]))
                    body = body.replace(edit["after"], edit["before"])
                original = (self.source / row["originalPath"]).read_text(encoding="utf-8")
                self.assertEqual(original, body)
                self.assertEqual(row["originalSha256LF"], sha(original))

    def test_generated_real_screen_defaults_to_long_and_keeps_existing_composer_routes(self):
        row = next(row for row in self.receipt["emitted"] if row["originalPath"] == ORIGINAL)
        body = (self.output / row["output"]).read_text(encoding="utf-8")
        self.assertIn("mutableStateOf(BangumiReviewType.LONG)", body)
        self.assertIn("BangumiShortReviewComposer(", body)
        self.assertIn('AppText("写短评")', body)
        self.assertIn('AppText("写长评")', body)
        self.assertIn("member.bilibili.com/article-text", body)
        self.assertNotIn("BangumiLongReviewComposer(", body)

    def test_full_producer_and_all_output_bytes_are_reproducible(self):
        second = Path(self.directory.name) / "second"
        self.run_producer(second)
        first_files = {p.relative_to(self.output): p.read_bytes() for p in self.output.rglob("*") if p.is_file()}
        second_files = {p.relative_to(second): p.read_bytes() for p in second.rglob("*") if p.is_file()}
        self.assertEqual(first_files, second_files)


if __name__ == "__main__":
    unittest.main()
