"""Run the real producers and restore their complete pinned original UI bodies."""
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
