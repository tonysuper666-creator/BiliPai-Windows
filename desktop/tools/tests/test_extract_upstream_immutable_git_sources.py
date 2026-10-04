"""Fresh, tagless-checkout regression for the audited alpha.9 Git readers."""
from pathlib import Path
import hashlib
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / "desktop/tools"
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

PIN = "fcf84853b287662e8a9129ea0d38576c36522a34"
FORMAT_SHA = "4aad65c3264e4f2f7b8acefb9df8f5cfb3e840d0090926ab1ec7ad94ce2e0ce3"


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


codec = load("immutable_static_codec", "extract-upstream-dynamic-static-image-codec.py")
detail = load("immutable_detail_container", "extract-upstream-dynamic-detail-container.py")


class ImmutableGitSourceTests(unittest.TestCase):
    def setUp(self):
        # The producers' long-path adapter is Windows-specific; use the native
        # filesystem on Linux policy runners without changing the Git read.
        if os.name != "nt":
            for module in (codec, detail):
                patch = mock.patch.object(module, "safe", lambda path: Path(path).absolute())
                patch.start()
                self.addCleanup(patch.stop)

    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix="bilipai-tagless-source-")
        cls.root = Path(cls.directory.name)
        cls.checkout = cls.root / "checkout"
        # Share local objects only; a fresh checkout deliberately has no tags or
        # generated cache. No network, credentials, native runtime or Gradle.
        subprocess.run(
            ["git", "clone", "--quiet", "--shared", "--no-checkout", "--no-tags",
             str(REPO), str(cls.checkout)], check=True,
        )
        tags = subprocess.run(
            ["git", "show-ref", "--tags"], cwd=cls.checkout, capture_output=True,
        )
        if tags.returncode != 1 or tags.stdout:
            raise AssertionError("Fixture unexpectedly contains local tags")
        cls.original = subprocess.check_output(
            ["git", "show", PIN + ":" + codec.ORIGINAL], cwd=cls.checkout,
        )

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def test_codec_generates_identical_bytes_without_a_local_tag(self):
        output = self.root / "codec-output"
        real_read = subprocess.check_output
        with mock.patch.object(codec.subprocess, "check_output", wraps=real_read) as read:
            declarations = codec.generate(self.checkout, output)
        read.assert_called_once_with(
            ["git", "show", PIN + ":" + codec.ORIGINAL], cwd=self.checkout,
        )
        files = list(output.rglob("*.kt"))
        self.assertEqual(1, len(files))
        self.assertEqual(FORMAT_SHA, hashlib.sha256(files[0].read_bytes()).hexdigest())
        self.assertEqual(4, len(declarations))
        self.assertEqual(PIN, codec.ORIGINAL_COMMIT)

    def test_codec_still_rejects_changed_original_bytes_before_emission(self):
        output = self.root / "tampered-codec-output"
        with mock.patch.object(codec.subprocess, "check_output", return_value=self.original + b"\n"):
            with self.assertRaises(AssertionError):
                codec.generate(self.checkout, output)
        self.assertFalse(output.exists())

    def test_standalone_detail_check_reads_the_same_immutable_commit(self):
        source = self.root / "original-detail-input.kt"
        source.write_bytes(self.original)
        real_run = subprocess.run
        with mock.patch.object(detail, "REPO", self.checkout), \
                mock.patch.object(detail, "STANDALONE", True), \
                mock.patch.object(detail, "_desktop_canonical_source", return_value=source), \
                mock.patch.object(detail.subprocess, "run", wraps=real_run) as read:
            actual = detail.read(codec.ORIGINAL)
        read.assert_called_once_with(
            ["git", "show", PIN + ":" + codec.ORIGINAL],
            cwd=self.checkout, capture_output=True, check=True,
        )
        self.assertEqual(self.original.decode("utf-8").replace("\r\n", "\n"), actual)
        self.assertEqual(PIN, detail.ORIGINAL_COMMIT)

    def test_standalone_detail_check_preserves_source_equality_guard(self):
        source = self.root / "changed-detail-input.kt"
        source.write_bytes(self.original + b"\n")
        with mock.patch.object(detail, "REPO", self.checkout), \
                mock.patch.object(detail, "STANDALONE", True), \
                mock.patch.object(detail, "_desktop_canonical_source", return_value=source):
            with self.assertRaises(AssertionError):
                detail.read(codec.ORIGINAL)


if __name__ == "__main__":
    unittest.main()
