from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "desktop/tools"))
import v037_window_modal_blur as modal


class WholeWindowsModalSheetTest(unittest.TestCase):
    def test_full_inverse_restores_fixed_original_with_real_windows_motion(self):
        original, identity = modal.fixed_original(REPO, "AppSheetComponents.kt")
        adapted, proof = modal.adapt_sheet(original)
        restored = adapted
        for change in reversed(proof["indexedEdits"]):
            start = change["afterOffset"]
            self.assertEqual(restored[start:start + len(change["after"])], change["after"])
            restored = restored[:start] + change["before"] + restored[start + len(change["after"]):]
        self.assertEqual(restored, original)
        self.assertEqual(identity["gitBlob"], "42624489b12087f8f9106246e2515153225f1868")
        self.assertIn("backgroundBlurBehind: Boolean = true", adapted)
        self.assertIn("WindowDialog(", adapted)
        self.assertIn("WindowBottomSheet(", adapted)
        self.assertIn("ModalBottomSheet(", adapted)
        self.assertIn("layout.findRootCoordinates().size.height", adapted)
        self.assertIn("neverEqualPolicy()", adapted)
        self.assertIn("coordinates.value = it", adapted)
        self.assertNotIn("LocalHingeSafeOverlayRegions", adapted)
        self.assertNotIn("HingeSafeOverlayHost", adapted)
        self.assertNotIn("LocalView", adapted)

    def test_existing_sole_producer_emits_verified_full_sheet(self):
        path = REPO / "desktop/tools/extract-upstream-dynamic-detail-container.py"
        spec = importlib.util.spec_from_file_location("whole_sheet_producer", path)
        producer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(producer)
        with tempfile.TemporaryDirectory(prefix="bp-whole-modal-") as directory:
            output = Path(directory)
            producer.generate(REPO, output)
            original, _ = modal.fixed_original(REPO, "AppSheetComponents.kt")
            adapted, _ = modal.adapt_sheet(original)
            source = output / "com/android/purebilibili/core/ui/DesktopOriginalDetailAppSheetComponents.kt"
            expected = "// Original source design-system/src/main/java/com/android/purebilibili/core/ui/AppSheetComponents.kt\n// LF SHA256 " + hashlib.sha256(original.encode()).hexdigest() + "\n" + adapted
            self.assertEqual(source.read_text(encoding="utf-8"), expected)
            proof = json.loads((output / "v037-whole-app-sheet-platform-proof.json").read_bytes())
            self.assertTrue(proof["exactCompleteInverse"])
            self.assertFalse(proof["sourceQualified"])

    def test_changed_original_bytes_are_rejected(self):
        with tempfile.TemporaryDirectory(prefix="bp-modal-pins-") as directory:
            repo = Path(directory)
            archive = Path("desktop/upstream-slices/v037-window-modal-blur")
            shutil.copytree(REPO / archive, repo / archive)
            path = repo / archive / "AppSheetComponents.kt"
            path.write_bytes(path.read_bytes() + b"\n// drift\n")
            with self.assertRaisesRegex(AssertionError, "Fixed modal bytes changed"):
                modal.fixed_original(repo, "AppSheetComponents.kt")

    def test_unknown_window_geometry_is_rejected(self):
        original, _ = modal.fixed_original(REPO, "AppSheetComponents.kt")
        with self.assertRaises(AssertionError):
            modal.adapt_sheet(original.replace("view.rootView.height", "unknownWindow.height"))


if __name__ == "__main__":
    unittest.main()
