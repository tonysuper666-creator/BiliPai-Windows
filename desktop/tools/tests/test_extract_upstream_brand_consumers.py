"""Actual sole producer + fixed full-body/inverse/default-call source contracts.

Private preparation may point the readonly canonical base and unchanged tooling
assembly at BILIPAI_BRAND_BASE_REPO/BILIPAI_BRAND_TOOLS. Normal repo tests need neither.
No Kotlin, Compose, native, profiles or networking are executed here.
"""
from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
BASE_REPO = Path(os.environ.get("BILIPAI_BRAND_BASE_REPO", str(REPO)))
TOOLS = Path(os.environ.get("BILIPAI_BRAND_TOOLS", str(REPO / "desktop/tools")))
sys.path.insert(0, str(TOOLS))
import v029_brand_consumers as brand
from v025_source_paths import canonical_source


class BrandConsumerProducerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="brand-consumer-source-")
        cls.root = Path(cls.temporary.name)
        cls.manifest, cls.originals = brand.checked_sources()
        cls.legacy = canonical_source(BASE_REPO, brand.LOTTIE).read_text(encoding="utf-8").replace("\r\n", "\n")
        env = os.environ.copy()
        env["PYTHONPATH"] = str(TOOLS) + os.pathsep + str(BASE_REPO / "desktop/tools")
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        cls.outputs = {}
        for name, folder in (("extract-upstream-home-page.py", "home"), ("extract-upstream-bgm-detail.py", "bgm")):
            output = cls.root / folder
            result = subprocess.run([sys.executable, "-X", "utf8", "-B", str(TOOLS / name),
                "--repo", str(BASE_REPO), "--output", str(output)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env)
            if result.returncode:
                cls.temporary.cleanup()
                raise AssertionError(result.stdout.decode("utf-8"))
            cls.outputs[folder] = output

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def assert_full_original_inverse(self, generated: str, audit: dict):
        original = self.originals[brand.LOTTIE]
        restored = generated
        for row in reversed(audit["countedAdaptations"]):
            index = row["index"]
            self.assertEqual(row["after"], restored[index:index + len(row["after"])])
            restored = restored[:index] + row["before"] + restored[index + len(row["after"]):]
        self.assertEqual(original.encode(), restored.encode())
        self.assertTrue(audit["exactFullSourceInverse"])
        self.assertEqual(brand.digest(original.encode()), audit["originalSha256Bytes"])
        a, b = brand.declaration_bounds(original, audit["declaration"])
        c, d = brand.declaration_bounds(generated, audit["declaration"])
        self.assertEqual(original[a:b], generated[c:d])

    def test_actual_home_sole_producer_preserves_complete_error_and_helpers(self):
        output = self.outputs["home"]
        body = (output / brand.PACKAGE_PATH / "DesktopHomeOriginalErrorState.kt").read_text(encoding="utf-8")
        inventory = json.loads((output / "home-page-producer-inventory.json").read_bytes())
        row = next(row for row in inventory if row.get("selectedDeclarations") == ["ErrorState"])
        self.assert_full_original_inverse(body, row["sourceAudit"])
        self.assertEqual(brand.COMMIT, row["sourceAudit"]["fixedUpstreamCommit"])
        for rel, original, audit in brand.shared_helpers():
            self.assertEqual(original.encode(), (output / rel).read_bytes())
            self.assertEqual([], audit["countedAdaptations"])
        combined = "\n".join(path.read_text(encoding="utf-8") for path in (output / "com").rglob("*.kt"))
        for name in ("ErrorState", "MaidStateViewport", "maidStateIllustrationSize"):
            self.assertEqual(1, len(re.findall(r"(?m)^(?:internal )?fun " + name + r"\(", combined)), name)

    def test_actual_bgm_sole_producer_preserves_complete_empty_and_legacy_loaders(self):
        output = self.outputs["bgm"]
        body = (output / brand.PACKAGE_PATH / "DesktopOriginalBgmEmptyState.kt").read_text(encoding="utf-8")
        identity = json.loads((output / "bgm-source-identities.json").read_bytes())
        audit = next(row for row in identity["sourceSelection"] if row.get("declaration") == "EmptyState")
        self.assert_full_original_inverse(body, audit)
        constants = re.search(r"(?m)^object LottieUrls \{[\s\S]*?\n\}", self.legacy).group()
        self.assertIn(constants, body)
        a, b = brand.declaration_bounds(self.legacy, "CutePersonLoadingIndicator")
        c, d = brand.declaration_bounds(body, "CutePersonLoadingIndicator")
        self.assertEqual(self.legacy[a:b], body[c:d])
        # BGM's registered source root is generated/bgm-detail/com; raw retained
        # reference Kotlin under bgm-original-retained is deliberately not compiled.
        combined = "\n".join(path.read_text(encoding="utf-8") for path in (output / "com").rglob("*.kt"))
        for name in ("EmptyState", "CutePersonLoadingIndicator"):
            self.assertEqual(1, len(re.findall(r"(?m)^fun " + name + r"\(", combined)), name)
        self.assertEqual(1, len(re.findall(r"(?m)^object LottieUrls \{", combined)))
        self.assertFalse(any(path.name in ("MaidStateViewport.kt", "DesktopOriginalMaidStateIllustration.kt") for path in output.rglob("*.kt")))

    def test_existing_positional_and_named_parameters_remain_compatible_with_defaults(self):
        for name, folder, filename in (("EmptyState", "bgm", "DesktopOriginalBgmEmptyState.kt"),
                                      ("ErrorState", "home", "DesktopHomeOriginalErrorState.kt")):
            output = (self.outputs[folder] / brand.PACKAGE_PATH / filename).read_text(encoding="utf-8")
            def parameters(raw):
                a, b = brand.declaration_bounds(raw, name)
                declaration = raw[a:b]
                header = declaration[:declaration.index(") {")]
                return re.findall(r"(?m)^\s+(\w+):[^\n]+", header)
            old_parameters = parameters(self.legacy)
            new_parameters = parameters(output)
            self.assertEqual(old_parameters, new_parameters[:len(old_parameters)])
            self.assertGreater(len(new_parameters), len(old_parameters))
            a, b = brand.declaration_bounds(output, name)
            header = output[a:b].split(") {", 1)[0]
            for new_name in new_parameters[len(old_parameters):]:
                self.assertRegex(header, r"(?m)^\s+" + new_name + r":[^\n]+=")

    def clone_archive(self, root: Path):
        for row in self.manifest["files"]:
            target = root / row["path"]
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(brand.wide(brand.ROOT / row["path"]).read_bytes())
        (root / "manifest.json").write_bytes(brand.wide(brand.ROOT / "manifest.json").read_bytes())

    def test_raw_crlf_or_body_tamper_is_rejected_for_each_complete_original(self):
        for row in self.manifest["files"]:
            with self.subTest(path=row["path"]), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                self.clone_archive(root)
                path = root / row["path"]
                path.write_bytes(path.read_bytes().replace(b"\n", b"\r\n"))
                with self.assertRaisesRegex(ValueError, "raw/blob mismatch"):
                    brand.checked_sources(root)

    def test_manifest_cannot_repin_modified_body(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.clone_archive(root)
            manifest_path = root / "manifest.json"
            manifest = json.loads(manifest_path.read_bytes())
            manifest["files"][0]["sha256Bytes"] = "0" * 64
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "manifest identity"):
                brand.checked_sources(root)

    def test_unknown_archive_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.clone_archive(root)
            (root / "extra.kt").write_text("unrelated source", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Unknown/missing"):
                brand.checked_sources(root)


if __name__ == "__main__":
    unittest.main()
