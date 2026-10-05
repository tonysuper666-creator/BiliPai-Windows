"""Pure source/resource producer contracts; no Kotlin, renderer, GUI or profile."""
from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
PRODUCER = REPO / "desktop/tools/extract-upstream-brand-motion.py"
spec = importlib.util.spec_from_file_location("brand_motion_producer", PRODUCER)
brand = importlib.util.module_from_spec(spec)
spec.loader.exec_module(brand)


class BrandMotionProducerTest(unittest.TestCase):
    def clone_archive(self, root: Path) -> None:
        source = REPO / brand.ARCHIVE
        for rel, path in brand.files_under(source).items():
            target = brand.wide(root / brand.ARCHIVE / rel)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(path.read_bytes())

    def test_actual_fresh_catalog_has_complete_original_inverse(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "generated"
            result = brand.generate(REPO, output)
            code = (output / brand.CATALOG_PATH).read_bytes()
            self.assertEqual("15291a833f8e9d90731bdb5c1f8707a63c9958f395fb6938f6b5b49b1910a6c9", hashlib.sha256(code).hexdigest())
            proof = json.loads((output / brand.PROOF_PATH).read_bytes())
            self.assertEqual(17, len(proof["countedAdaptations"]))
            manifest, originals = brand.checked_inputs(REPO)
            original = originals[brand.BLUE_SNOW].decode("utf-8")
            start = original.index("enum class MaidAnimation(")
            end = original.index("\n}", start) + 2
            restored_enum = code.decode("utf-8")
            for edit in reversed(proof["countedAdaptations"]):
                self.assertEqual(1, restored_enum.count(edit["after"]), edit["label"])
                restored_enum = restored_enum.replace(edit["after"], edit["before"], 1)
            self.assertEqual(original[start:end], restored_enum)
            self.assertEqual(originals[brand.BLUE_SNOW], (original[:start] + restored_enum + original[end:]).encode("utf-8"))
            self.assertEqual(30, len(manifest["files"]))
            self.assertEqual(proof["fullSourceInverseSha256Bytes"], hashlib.sha256(originals[brand.BLUE_SNOW]).hexdigest())
            self.assertTrue(result["fullSourceInverse"])

    def test_runtime_pairs_are_raw_exact_and_welcome_is_archive_only(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "generated"
            brand.generate(REPO, output)
            proof = json.loads((output / brand.PROOF_PATH).read_bytes())
            catalog = {entry["animation"]: entry for entry in proof["catalog"]}
            self.assertEqual(15, len(catalog))
            self.assertFalse(catalog["WELCOME"]["runtimeAllowed"])
            self.assertTrue(catalog["CLEANING"]["loopsWhileVisible"])
            self.assertEqual(1600, catalog["CLEANING"]["durationMs"])
            for field in ("jsonOriginalPath", "pngOriginalPath", "durationMs"):
                self.assertEqual(catalog["DOWNLOAD_COMPLETE"][field], catalog["LIKE_SUCCESS"][field])
            resources = brand.files_under(output / "resources/brand-motion")
            self.assertEqual(13, sum(name.endswith(".json") for name in resources))
            self.assertEqual(13, sum(name.endswith(".png") for name in resources))
            for entry in catalog.values():
                for field in ("jsonOriginalPath", "pngOriginalPath"):
                    original_path = entry[field]
                    target = output / "resources/brand-motion" / Path(original_path).name
                    if entry["runtimeAllowed"]:
                        self.assertEqual(brand.wide(REPO / brand.ARCHIVE / original_path).read_bytes(), target.read_bytes())
                    else:
                        self.assertFalse(target.exists())
            self.assertEqual(33, len(brand.files_under(output)))
            self.assertEqual(1, sum(path.endswith("BlueSnowMaidAnimation.kt") for path in brand.files_under(output)))
            self.assertFalse(any(path.endswith("ReduceMotion.kt") for path in brand.files_under(output)))

    def test_complete_blue_snow_ui_has_its_own_exact_inverse(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "generated"
            brand.generate(REPO, output)
            proof = json.loads((output / brand.UI_PROOF_PATH).read_bytes())
            code = (output / brand.UI_PATH).read_text(encoding="utf-8")
            original = brand.wide(REPO / brand.ARCHIVE / brand.BLUE_SNOW).read_bytes()
            self.assertEqual(20, len(proof["countedAdaptations"]))
            self.assertEqual(original, brand.brand_ui.reverse_blue_snow(code, proof["countedAdaptations"]).encode("utf-8"))
            self.assertEqual(proof["originalSha256Bytes"], proof["fullSourceInverseSha256Bytes"])
            self.assertEqual(brand.sha256(code.encode("utf-8")), proof["generatedSha256Bytes"])
            changed = code.replace("LottieCompositionSpec.Animation(animation)", "LottieCompositionSpec.Animation(other)")
            with self.assertRaisesRegex(ValueError, "inverse mismatch"):
                brand.brand_ui.reverse_blue_snow(changed, proof["countedAdaptations"])

    def test_json_tamper_rejected_before_any_generation(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp) / "repo"
            self.clone_archive(repo)
            path = repo / brand.ARCHIVE / "brand-motion/src/main/res/raw/bilipai_maid_empty.json"
            path.write_bytes(path.read_bytes().replace(b'"fr":60', b'"fr":61', 1))
            with self.assertRaisesRegex(ValueError, "raw byte/blob identity"):
                brand.generate(repo, Path(temp) / "generated")
            self.assertFalse((Path(temp) / "generated").exists())

    def test_png_tamper_rejected_before_any_generation(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp) / "repo"
            self.clone_archive(repo)
            path = repo / brand.ARCHIVE / "brand-motion/src/main/res/drawable-nodpi/bilipai_maid_empty_static.png"
            data = path.read_bytes()
            path.write_bytes(data[:-1] + bytes([data[-1] ^ 1]))
            with self.assertRaisesRegex(ValueError, "raw byte/blob identity"):
                brand.generate(repo, Path(temp) / "generated")
            self.assertFalse((Path(temp) / "generated").exists())

    def test_source_crlf_normalization_is_not_accepted_as_original(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp) / "repo"
            self.clone_archive(repo)
            path = repo / brand.ARCHIVE / brand.BLUE_SNOW
            data = path.read_bytes()
            self.assertNotIn(b"\r\n", data)
            path.write_bytes(data.replace(b"\n", b"\r\n"))
            with self.assertRaisesRegex(ValueError, "raw byte/blob identity"):
                brand.generate(repo, Path(temp) / "generated")
            self.assertFalse((Path(temp) / "generated").exists())

    def test_manifest_cannot_repin_modified_original(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp) / "repo"
            self.clone_archive(repo)
            path = repo / brand.ARCHIVE / brand.REDUCE_MOTION
            data = path.read_bytes() + b"\n"
            path.write_bytes(data)
            mp = repo / brand.ARCHIVE / "manifest.json"
            manifest = json.loads(mp.read_bytes())
            row = next(row for row in manifest["files"] if row["path"] == brand.REDUCE_MOTION)
            row.update(bytes=len(data), sha256Bytes=brand.sha256(data), gitBlob=brand.git_blob(data))
            mp.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "manifest SHA256"):
                brand.generate(repo, Path(temp) / "generated")
            self.assertFalse((Path(temp) / "generated").exists())

    def test_unknown_archive_file_is_not_ignored(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp) / "repo"
            self.clone_archive(repo)
            (repo / brand.ARCHIVE / "foreign.txt").write_bytes(b"foreign")
            with self.assertRaisesRegex(ValueError, "unknown files"):
                brand.generate(repo, Path(temp) / "generated")

    def test_existing_unknown_or_modified_output_is_preserved_and_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "generated"
            brand.generate(REPO, output)
            original_files = {path: p.read_bytes() for path, p in brand.files_under(output).items()}
            self.assertEqual(brand.generate(REPO, output), brand.generate(REPO, output))
            foreign = output / "user.txt"
            foreign.write_bytes(b"preserve")
            with self.assertRaisesRegex(ValueError, "unknown files"):
                brand.generate(REPO, output)
            self.assertEqual(b"preserve", foreign.read_bytes())
            self.assertEqual(original_files, {path: (output / path).read_bytes() for path in original_files})
            foreign.unlink()
            catalog = output / brand.CATALOG_PATH
            catalog.write_bytes(b"foreign catalog")
            with self.assertRaisesRegex(ValueError, "Existing generated output differs"):
                brand.generate(REPO, output)
            self.assertEqual(b"foreign catalog", catalog.read_bytes())

    def test_output_cannot_overwrite_raw_archive(self):
        with self.assertRaisesRegex(ValueError, "overlaps"):
            brand.generate(REPO, REPO / brand.ARCHIVE / "generated")


if __name__ == "__main__":
    unittest.main()
