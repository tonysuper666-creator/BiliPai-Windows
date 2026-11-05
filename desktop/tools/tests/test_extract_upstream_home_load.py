import contextlib
import hashlib
import importlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))
from v029_home_load import COMMIT, PINS, sources, wide
import desktop_refresh_adaptation as refresh

# Later Home page layers write into the same v029 receipt, in the producer's own order:
# e3a868515 (search dependencies), 2f8c44ad9 (native cards, recommendation badges) and
# 84a9aefbc (feed density). The v029 stage is the same producer with exactly these disabled.
LATER_PAGE_LAYERS = (
    ("v042_search_dependencies", "adapt_home"),
    ("v043_home_native_cards", "home_page_delta"),
    ("v043_home_recommendation_badges", "apply_card"),
    ("ux_home_layout", "apply_home_layout"),
)
# 591685d9e binds the desktop refresh launch after the v029 VM delta and records no receipt.
REFRESH_LAUNCH = "import com.bilipai.desktop.ui.desktopRefreshLaunch as launch\n"


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
        page = module("contract_home_page", "extract-upstream-home-page.py")
        page.generate(REPO, cls.output / "page")
        with contextlib.ExitStack() as stack:
            for name, function in LATER_PAGE_LAYERS:
                stack.enter_context(mock.patch.object(
                    importlib.import_module(name), function, lambda repo, target, body, audits: body))
            page.generate(REPO, cls.output / "page-v029")

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

    def unique(self, group, name):
        hits = [path for path in Path(wide(self.output / group)).rglob(name) if path.is_file()]
        self.assertEqual(len(hits), 1, (group, name, hits))
        return hits[0].read_text(encoding="utf8")

    def vm_stage(self):
        final = self.body("vm", "DesktopOriginalHomeViewModel.kt")
        self.assertEqual(final.count(REFRESH_LAUNCH), 1)
        stage = final.replace(REFRESH_LAUNCH, "import kotlinx.coroutines.launch\n")
        self.assertEqual(refresh.bind_refresh_launch(stage), final)
        return stage

    def test_all_fresh_deltas_have_complete_sequential_inverse(self):
        vm_stage = self.vm_stage()
        for group, metadata in (("vm", "home-load-v029-adaptations.json"), ("page-v029", "home-page-v029-load-adaptations.json")):
            receipt = json.loads((self.output / group / metadata).read_text(encoding="utf8"))
            self.assertEqual(receipt["upstreamCommit"], COMMIT)
            self.assertTrue(receipt["audits"])
            for audit in receipt["audits"]:
                if group == "vm":
                    self.assertEqual(audit["output"], "DesktopOriginalHomeViewModel.kt")
                    body = vm_stage
                else:
                    body = self.unique(group, audit["output"])
                self.assertEqual(hashlib.sha256(body.encode()).hexdigest(), audit["afterSha256LF"])
                for edit in reversed(audit["edits"]):
                    i = edit["offset"]
                    self.assertEqual(body[i:i+len(edit["after"])], edit["after"])
                    body = body[:i] + edit["before"] + body[i+len(edit["after"]):]
                self.assertEqual(hashlib.sha256(body.encode()).hexdigest(), audit["beforeSha256LF"])

    def test_actual_page_is_exactly_the_later_layers_over_the_v029_stage(self):
        metadata = "home-page-v029-load-adaptations.json"
        stage_audits = json.loads((self.output / "page-v029" / metadata).read_text(encoding="utf8"))["audits"]
        actual_audits = json.loads((self.output / "page" / metadata).read_text(encoding="utf8"))["audits"]
        self.assertEqual([audit for audit in actual_audits if audit in stage_audits], stage_audits)
        def files(group):
            root = Path(wide(self.output / group))
            return sorted(path.relative_to(root).as_posix() for path in root.rglob("*") if path.is_file())
        self.assertEqual(files("page-v029"), files("page"))
        layers = [getattr(importlib.import_module(name), function) for name, function in LATER_PAGE_LAYERS]
        later = []
        for relative in files("page"):
            stage = wide(self.output / "page-v029" / relative).read_bytes()
            actual = wide(self.output / "page" / relative).read_bytes()
            if relative == metadata:
                continue
            if not relative.endswith(".kt"):
                self.assertEqual(stage, actual, relative)
                continue
            body = stage.decode("utf8")
            for layer in layers:
                body = layer(REPO, Path(relative).name, body, later)
            self.assertEqual(body, actual.decode("utf8"), relative)
        key = lambda audit: json.dumps(audit, ensure_ascii=False, sort_keys=True)
        rest = [audit for audit in actual_audits if audit not in stage_audits]
        self.assertTrue(rest)
        self.assertEqual(sorted(map(key, later)), sorted(map(key, rest)))

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
