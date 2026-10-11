"""Run the real producers and restore their complete pinned original UI bodies."""
import hashlib
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


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def body(module, text, declaration):
    start = text.index(declaration)
    masked = module.protocol.masked(text)
    brace = masked.index("{", start)
    end = module.protocol.balanced(masked, brace, "{", "}")
    return text[start:end]


class OriginalSubtitleAndDownloadSourceTest(unittest.TestCase):
    def test_complete_b209_sponsor_ui_and_vote_parameters_use_the_fixed_sole_producer(self):
        producer = load("whole_sponsor_ui_contract", "extract-upstream-video-player-section-full.py")
        original_path = "app/src/main/java/com/android/purebilibili/feature/video/ui/components/SponsorSkipUI.kt"
        path = "com/android/purebilibili/feature/video/ui/components/SponsorSkipUI.kt"
        archive = REPO / "desktop/upstream-slices/v034-sponsor-ui"
        source_path = archive / "SponsorSkipUI.kt"
        raw = source_path.read_bytes()
        manifest_raw = (archive / "manifest.json").read_bytes()
        expected_manifest = "be51c84bac1fa043b40be2446a086b25df7e85eca1be8953872be5e34fa5d9a8"
        expected_source = "bf4f74363fe6150a5429cb6f5c00c9071e8341805c76bc5cdee8dfbafb87b462"
        self.assertEqual(producer.SPONSOR_UI_MANIFEST_SHA256, expected_manifest)
        self.assertEqual(hashlib.sha256(manifest_raw).hexdigest(), expected_manifest)
        self.assertEqual(len(raw), 13343)
        self.assertEqual(hashlib.sha256(raw).hexdigest(), expected_source)
        self.assertNotIn(b"\r\n", raw)
        with tempfile.TemporaryDirectory(prefix="bp-whole-sponsor-ui-") as temporary:
            output = Path(temporary)
            rows = producer.generate(REPO, output / "actual", True)
            row = next(item for item in rows if item["path"] == path)
            self.assertEqual(row["mode"], "direct-complete-original")
            self.assertTrue(row["generated"])
            self.assertEqual(row["sha256LF"], expected_source)
            self.assertEqual((output / "actual" / path).read_bytes(), raw, "All original UI declarations and vote handlers remain intact")
            self.assertEqual(row["source"], {
                "upstreamCommit": "b2091b1de636e499df80af7e700796b4f52b7f92",
                "originalPath": original_path, "sha256LF": expected_source,
                "gitBlob": "ca6dc93eddbd2319adbbd13a245bcd97bb4e2e35",
                "localPath": "desktop/upstream-slices/v034-sponsor-ui/SponsorSkipUI.kt",
                "sourceManifestSha256": expected_manifest,
            })
            canonical_raw = producer._desktop_canonical_source(REPO, original_path).read_bytes().replace(b"\r\n", b"\n")
            self.assertEqual(hashlib.sha256(canonical_raw).hexdigest(), "b2dcee93edfcd618df90037d1d6fae6b94e011c13f39f0c8f3d5eeb57cfe1f37")
            self.assertEqual(producer.SOURCE_PINS[original_path]["sha256LF"], hashlib.sha256(canonical_raw).hexdigest())
            self.assertEqual(canonical_raw.count(b"onVote(-1)"), 1)
            self.assertEqual(raw.count(b"onVote(1)"), 1)
            self.assertEqual(raw, canonical_raw.replace(b"onVote(-1)", b"onVote(0)", 1), "The original negative-vote parameter change is the only upstream UI delta")
            self.assertEqual(canonical_raw, raw.replace(b"onVote(0)", b"onVote(-1)", 1), "Complete upstream-to-canonical inverse must remain exact")
            with mock.patch.object(producer, "SPONSOR_UI_MANIFEST_SHA256", "0" * 64):
                with self.assertRaisesRegex(AssertionError, "fixed whole sponsor UI manifest"):
                    producer.generate(REPO, output / "bad-manifest", True)
            changed = output / "changed-SponsorSkipUI.kt"
            changed.write_bytes(raw + b"\n// changed original\n")
            original_wide = producer.wide
            def source_bytes(path):
                return changed if Path(path) == source_path else original_wide(path)
            with mock.patch.object(producer, "wide", side_effect=source_bytes):
                with self.assertRaisesRegex(AssertionError, "fixed whole sponsor UI bytes"):
                    producer.generate(REPO, output / "bad-original", True)
        self.assertEqual(source_path.read_bytes(), raw)
        self.assertEqual((archive / "manifest.json").read_bytes(), manifest_raw)

    def test_complete_subtitle_host_restores_the_pinned_original_body(self):
        producer = load("whole_subtitle_host_contract", "extract-upstream-video-player-section-full.py")
        path = "com/android/purebilibili/feature/video/ui/section/VideoPlayerSection.kt"
        with tempfile.TemporaryDirectory(prefix="bp-whole-subtitle-host-") as temporary:
            output = Path(temporary)
            producer.generate(REPO, output, True)
            actual = (output / path).read_text(encoding="utf-8")
            recovered = body(producer, actual, "internal fun BoxScope.VideoSubtitleOverlayHost(")
            labels = (
                "Expose the whole original subtitle host to the Windows physical leaf",
                "Optional captured Windows subtitle position writer; original callers retain their setter",
                "A Windows source replacement cancels the old subtitle drag before its writer can be reused",
                "Whole original drag saves through the captured Windows settings admission",
            )
            edits = [row for row in producer.EDITS if row["label"] in labels]
            self.assertEqual(list(labels), [row["label"] for row in edits])
            for row in reversed(edits):
                self.assertEqual(1, recovered.count(row["after"]))
                recovered = recovered.replace(row["after"], row["before"], 1)
            # The sole previously existing platform-context adaptation remains.
            adapted_context = "val context = LocalDesktopOriginalVideoSectionPlatform.current.settingsContext"
            self.assertEqual(1, recovered.count(adapted_context))
            recovered = recovered.replace(adapted_context, "val context = LocalContext.current", 1)
            adapted_setter = "com.android.purebilibili.core.store.DesktopOriginalPlayerSectionSettings.setSubtitleVerticalOffsetFraction"
            self.assertEqual(1, recovered.count(adapted_setter))
            recovered = recovered.replace(adapted_setter, "SettingsManager.setSubtitleVerticalOffsetFraction", 1)
            original = producer.source("feature/video/ui/section/VideoPlayerSection.kt")
            expected = body(producer, original, "private fun BoxScope.VideoSubtitleOverlayHost(")
            self.assertEqual(expected, recovered)

    def test_download_speed_spans_are_exact_later_source_and_whole_list_restores(self):
        producer = load("whole_download_speed_contract", "extract-upstream-download-list.py")
        with tempfile.TemporaryDirectory(prefix="bp-whole-download-speed-") as temporary:
            output = Path(temporary)
            producer.generate(REPO, output, True)
            receipt = json.loads((output / "source-receipt.json").read_text(encoding="utf-8"))
            update, = receipt["sourceUpdates"]
            self.assertEqual("b2091b1de636e499df80af7e700796b4f52b7f92", update["fixedCommit"])
            raw = producer.safe(REPO / update["archive"]).read_bytes()
            self.assertEqual(update["rawSha256"], hashlib.sha256(raw).hexdigest())
            later = raw.decode("utf-8").replace("\r\n", "\n")
            self.assertEqual(later, (output / update["fullOriginalRetained"]).read_text(encoding="utf-8"))
            source = update["source"]
            row = next(item for item in receipt["sources"] if item["source"] == source)
            actual = (output / row["output"]).read_text(encoding="utf-8")
            edits = receipt["exactDeltas"][source]
            self.assertEqual(2, len(update["selections"]))
            for selection in update["selections"]:
                selected = later[selection["sourceStart"]:selection["sourceEnd"]]
                self.assertEqual(selection["afterSha256LF"], producer.sha(selected))
                self.assertEqual(1, sum(item["after"] == selected for item in edits))
                self.assertEqual(1, actual.count(selected))
            recovered = actual
            for edit in reversed(edits):
                for at in reversed(edit["afterOffsets"]):
                    self.assertEqual(edit["after"], recovered[at:at + len(edit["after"])])
                    recovered = recovered[:at] + edit["before"] + recovered[at + len(edit["after"]):]
            self.assertEqual(row["originalSha256LF"], producer.sha(recovered))
            self.assertEqual(producer.read(producer._desktop_canonical_source(REPO, source)), recovered)
