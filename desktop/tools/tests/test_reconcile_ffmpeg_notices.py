import importlib.util
import io
import json
from pathlib import Path
import tarfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
FIXTURES = Path(__file__).resolve().parent / "fixtures/ffmpeg-notices"
spec = importlib.util.spec_from_file_location("notice_audit", ROOT / "desktop/tools/reconcile-ffmpeg-missing-notices.py")
AUDIT = importlib.util.module_from_spec(spec)
spec.loader.exec_module(AUDIT)


class ExactNoticeSourceTest(unittest.TestCase):
    def inputs(self, name):
        pin = AUDIT.PINS[name]
        catalog = json.loads((ROOT / "desktop/third-party/ffmpeg/SOURCES.json").read_text(encoding="utf-8"))
        entry = next(item for item in catalog["dependencyLicenseInventory"]["rav1eLockedCrates"]["packages"] if item["name"] == name)
        crate = (FIXTURES / f"{name}-{pin['version']}.crate").read_bytes()
        source = (FIXTURES / f"{name}-{pin['commit']}.zip").read_bytes()
        notice = (ROOT / "desktop/third-party/ffmpeg/licenses" / f"rav1e-crate-{name}-{pin['version']}-upstream-LICENSE").read_bytes()
        return pin, entry["sourceArchiveSha256"], crate, source, notice

    def test_exact_published_sources_and_upstream_notice_are_reconciled(self):
        for name, expected_count in [("difflib", 6), ("simd_helpers", 2)]:
            pin, checksum, crate, source, notice = self.inputs(name)
            self.assertEqual(expected_count, len(AUDIT.verify_source(name, pin, crate, checksum, source, notice)))

    def test_tampered_notice_and_source_archives_are_rejected(self):
        pin, checksum, crate, source, notice = self.inputs("difflib")
        with self.assertRaisesRegex(ValueError, "notice checksum"):
            AUDIT.verify_source("difflib", pin, crate, checksum, source, notice + b"altered")
        with self.assertRaisesRegex(ValueError, "archive checksum"):
            AUDIT.verify_source("difflib", pin, crate, checksum, source + b"altered", notice)

    def test_notice_cannot_be_assigned_to_even_one_changed_published_rust_file(self):
        pin, checksum, crate, source, notice = self.inputs("simd_helpers")
        rewritten = io.BytesIO()
        with tarfile.open(fileobj=io.BytesIO(crate), mode="r:gz") as archive, tarfile.open(fileobj=rewritten, mode="w:gz") as target:
            for member in archive.getmembers():
                payload = archive.extractfile(member).read() if member.isfile() else None
                if member.name.endswith("src/lib.rs"):
                    payload += b"\n// source drift\n"; member.size = len(payload)
                target.addfile(member, io.BytesIO(payload) if payload is not None else None)
        changed = rewritten.getvalue()
        with self.assertRaisesRegex(ValueError, "Rust source differs"):
            AUDIT.verify_source("simd_helpers", pin, changed, AUDIT.sha256(changed), source, notice)

    def test_review_output_cannot_modify_live_native_files(self):
        with self.assertRaisesRegex(ValueError, "live native"):
            AUDIT.prepare(ROOT, FIXTURES, ROOT / "desktop/native/windows-x64/licenses/new-notices")


if __name__ == "__main__": unittest.main()
