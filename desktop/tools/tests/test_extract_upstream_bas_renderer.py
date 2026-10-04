import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("bas_renderer", REPO / "desktop/tools/extract-upstream-bas-renderer.py")
producer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(producer)


class BasRendererSourceContractTest(unittest.TestCase):
    def test_fresh_generator_preserves_complete_original_painter_and_frame_input_inverse(self):
        with tempfile.TemporaryDirectory(prefix="bas-raster-source-") as temporary:
            output = Path(temporary)
            paths = producer.generate(REPO, output)
            self.assertEqual(2, len(paths))
            proof = json.loads((output / "source-proof.json").read_text(encoding="utf-8"))
            self.assertTrue(proof["wholePainterInverse"])
            self.assertTrue(proof["retainedConfigureFrameHitInputInverse"])
            originals = producer.checked(REPO)
            generated = paths[0].read_text(encoding="utf-8")
            self.assertEqual(originals["BasScenePainter.kt"], producer.inverse(generated, proof["painterEdits"]))
            self.assertNotIn("import android.", generated)
            self.assertNotIn("android.opengl.", generated)
            self.assertIn("canvas.concat(node.screen)", generated)
            self.assertIn("node.inverse.mapPoints(point)", generated)
            self.assertIn("canvas.clipRect(0f, 0f, state.width, state.height)", generated)
            self.assertIn("budget.text(state, size)", generated)
            self.assertIn("budget.projection(state, node.screen)", generated)
            self.assertIn("node.layout?.close()", generated)
            for path in paths:
                relative = path.relative_to(output).as_posix()
                self.assertEqual(proof["outputSha256"][relative], hashlib.sha256(path.read_bytes()).hexdigest())
            self.assertFalse(proof["actualMainBasRendered"])

    def test_raw_painter_and_manifest_tampering_fail_before_output(self):
        with tempfile.TemporaryDirectory(prefix="bas-painter-tamper-") as temporary:
            repo = Path(temporary) / "repo"
            archive = repo / producer.ARCHIVE
            shutil.copytree(REPO / producer.ARCHIVE, archive)
            painter = archive / "BasScenePainter.kt"
            raw = painter.read_bytes()
            painter.write_bytes(raw.replace(b"rotateX", b"rotateQ", 1))
            with self.assertRaises(ValueError): producer.generate(repo, Path(temporary) / "output")
            self.assertFalse((Path(temporary) / "output").exists())
            painter.write_bytes(raw)
            manifest = archive / "manifest.json"
            manifest.write_bytes(manifest.read_bytes() + b"\n")
            with self.assertRaises(ValueError): producer.checked(repo)

    def test_unknown_output_and_input_overlap_do_not_overwrite_originals(self):
        with tempfile.TemporaryDirectory(prefix="bas-painter-output-") as temporary:
            output = Path(temporary)
            sentinel = output / "unknown.kt"
            sentinel.write_bytes(b"keep")
            with self.assertRaises(ValueError): producer.generate(REPO, output)
            self.assertEqual(b"keep", sentinel.read_bytes())
            with self.assertRaises(ValueError): producer.generate(REPO, REPO / producer.ARCHIVE)

    def test_whole_adaptation_rejects_missing_geometry_or_input_anchor(self):
        originals = producer.checked(REPO)
        with self.assertRaises(ValueError):
            producer.painter(originals["BasScenePainter.kt"].replace("node.screen.setValues(projection)", "unexpectedProjection(projection)"))
        with self.assertRaises(ValueError):
            producer.frames(originals["BasDanmakuOverlay.kt"].replace("activate(target)", "unexpectedActivation(target)"))

    def test_repeated_generation_is_exact_and_never_edits_fixed_core(self):
        core = REPO / "desktop/upstream-slices/v029-bas-core"
        before = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in core.iterdir() if p.is_file()}
        with tempfile.TemporaryDirectory(prefix="bas-render-repeat-") as temporary:
            output = Path(temporary)
            paths = producer.generate(REPO, output)
            first = [p.read_bytes() for p in paths]
            self.assertEqual(first, [p.read_bytes() for p in producer.generate(REPO, output)])
        self.assertEqual(before, {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in core.iterdir() if p.is_file()})


if __name__ == "__main__": unittest.main()
