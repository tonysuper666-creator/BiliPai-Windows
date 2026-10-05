import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))
from v029_home_load import COMMIT, PINS, sources, wide


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value


class HomeLoadSourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="home-load-contract-")
        cls.output = Path(cls.temp.name)
        module("contract_home_vm", "extract-upstream-home-viewmodel.py").generate(
            REPO, cls.output / "vm", test_output=cls.output / "tests")
        module("contract_home_page", "extract-upstream-home-page.py").generate(REPO, cls.output / "page")

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def body(self, group, name):
        return wide(self.output / group / "com/android/purebilibili/feature/home" / name).read_text(encoding="utf8")

    def test_actual_fresh_vm_consumes_fixed_failure_reducer_in_all_feed_paths(self):
        body = self.body("vm", "DesktopOriginalHomeViewModel.kt")
        self.assertEqual(body.count("internal fun applyHomeFeedLoadFailure("), 1)
        self.assertEqual(body.count("applyHomeFeedLoadFailure("), 6)  # Definition + five actual failure consumers.
        self.assertNotIn("hasMore = if (shouldKeepHomeCategoryAutoPagingAfterFailure", body)
        self.assertIn("return !isLoadMore", body)  # The already-equal user-info policy is retained.
        self.assertIn("desktopHomeLoadLocal.get()?.let", body)
        self.assertIn("desktopHomeSelection === request.selection", body)
        self.assertIn("request.caller.isActive", body)
        self.assertIn("desktopHomeRefresh === request", body)

    def test_actual_fresh_page_keeps_retry_and_stops_automatic_paging_on_error(self):
        page = self.body("page", "HomeCategoryPage.kt")
        screen = self.body("page", "DesktopOriginalHomeScreen.kt")
        self.assertIn("latestIsActive && latestCategoryState.loadMoreError == null", page)
        self.assertIn('item(key = "refresh_error"', page)
        self.assertIn('item(key = "load_more_error"', page)
        self.assertIn("onRetry = onRetryLoadMore", page)
        self.assertIn("onRetry = onRetryRefresh", page)
        self.assertIn("viewModel.loadMoreIfSelected(category, popularSubCategory)", screen)
        self.assertIn("viewModel.refreshIfSelected(category, selectedPopularSubCategory)", screen)
        # There is already one full original ListLoadError producer. No duplicate is emitted here.
        self.assertFalse(wide(self.output / "page/com/android/purebilibili/feature/common/ListLoadError.kt").exists())

    def test_all_fresh_deltas_have_complete_sequential_inverse(self):
        for group, metadata in (("vm", "home-load-v029-adaptations.json"), ("page", "home-page-v029-load-adaptations.json")):
            receipt = json.loads((self.output / group / metadata).read_text(encoding="utf8"))
            self.assertEqual(receipt["upstreamCommit"], COMMIT)
            for audit in receipt["audits"]:
                body = self.body(group, audit["output"])
                self.assertEqual(hashlib.sha256(body.encode()).hexdigest(), audit["afterSha256LF"])
                for edit in reversed(audit["edits"]):
                    i = edit["offset"]
                    self.assertEqual(body[i:i+len(edit["after"])], edit["after"])
                    body = body[:i] + edit["before"] + body[i+len(edit["after"]):]
                self.assertEqual(hashlib.sha256(body.encode()).hexdigest(), audit["beforeSha256LF"])

    def test_original_four_tests_are_whole_and_byte_identical(self):
        raw = wide(self.output / "tests/com/android/purebilibili/feature/home/HomeLoadSequencePolicyTest.kt").read_bytes()
        path = "app/src/test/java/com/android/purebilibili/feature/home/HomeLoadSequencePolicyTest.kt"
        self.assertEqual(hashlib.sha256(raw).hexdigest(), PINS[path])
        self.assertEqual(raw.count(b"@Test"), 4)

    def test_raw_tampering_and_unknown_commit_fail_closed(self):
        original = REPO / "desktop/upstream-slices/v029-home-load"
        with tempfile.TemporaryDirectory(prefix="home-load-raw-") as temp:
            root = Path(temp)
            target = root / "desktop/upstream-slices/v029-home-load"
            for path in ("manifest.json", *PINS):
                out = wide(target / path); out.parent.mkdir(parents=True, exist_ok=True)
                out.write_bytes(wide(original / path).read_bytes())
            path = next(iter(PINS)); item = wide(target / path)
            item.write_bytes(item.read_bytes()+b"\n")
            with self.assertRaisesRegex(ValueError, "raw identity"):
                sources(root)
            item.write_bytes(wide(original / path).read_bytes())
            manifest = json.loads((target / "manifest.json").read_text(encoding="utf8"))
            manifest["commit"] = "0" * 40
            (target / "manifest.json").write_text(json.dumps(manifest), encoding="utf8")
            with self.assertRaisesRegex(ValueError, "Unknown"):
                sources(root)


if __name__ == "__main__":
    unittest.main()
