import contextlib
import importlib.util
import io
import json
from pathlib import Path
import unittest

_PATH = Path(__file__).resolve().parents[1] / "native" / "import-host-llvm-source-snapshot.py"
_SPEC = importlib.util.spec_from_file_location("host_snapshot_importer_under_test", _PATH)
IMPORTER = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(IMPORTER)


def directory(path):
    return {"path": path, "kind": "directory", "mode": 0o755}


def link(path, target):
    return {"path": path, "kind": "symlink", "mode": 0o777, "target": target}


def file(path):
    return {"path": path, "kind": "file", "mode": 0o755, "bytes": 0,
            "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}


class HostLlvmLinkValidationTests(unittest.TestCase):
    def missing(self, root, target="missing", absolute_prefix=None):
        rows = [directory(root), directory(root + "/bin"), link(root + "/bin/tool", target)]
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaisesRegex(
                RuntimeError, "^HOST_LLVM_IMPORT_REJECTED: Dangling symlink path component$"):
            IMPORTER.validate_links(rows, root, absolute_prefix)
        prefix = "HOST LLVM link rejection: "
        self.assertTrue(stderr.getvalue().startswith(prefix))
        return json.loads(stderr.getvalue()[len(prefix):])

    def test_both_real_callers_identify_missing_inventory_component(self):
        for root in ("host-install", "actual-used-source-worktree"):
            with self.subTest(root=root):
                record = self.missing(root)
                self.assertEqual(record["kind"], "BILIPAI_HOST_LLVM_MISSING_LINK_COMPONENT")
                self.assertEqual(record["link"]["path"], root + "/bin/tool")
                self.assertEqual(record["missingComponent"]["path"], root + "/bin/missing")
                self.assertFalse(record["link"]["withheld"])
                self.assertFalse(record["missingComponent"]["withheld"])

    def test_original_absolute_prefix_is_mapped_without_being_logged(self):
        original = "/private/build-location/clang-root"
        record = self.missing("host-install", original + "/bin/missing", original)
        self.assertEqual(record["missingComponent"]["path"], "host-install/bin/missing")
        self.assertNotIn(original, json.dumps(record))

    def test_complete_link_chain_remains_accepted_without_diagnostic(self):
        root = "host-install"
        rows = [directory(root), directory(root + "/bin"),
                link(root + "/bin/tool", "second"), link(root + "/bin/second", "real"),
                file(root + "/bin/real")]
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            self.assertIsNone(IMPORTER.validate_links(rows, root))
        self.assertEqual(stderr.getvalue(), "")

    def test_missing_component_after_link_redirection_is_identified(self):
        root = "host-install"
        rows = [directory(root), directory(root + "/bin"),
                link(root + "/bin/tool", "second"), link(root + "/bin/second", "missing")]
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaisesRegex(
                RuntimeError, "Dangling symlink path component$"):
            IMPORTER.validate_links(rows, root)
        record = json.loads(stderr.getvalue().split(": ", 1)[1])
        self.assertEqual(record["link"]["path"], root + "/bin/tool")
        self.assertEqual(record["missingComponent"]["path"], root + "/bin/missing")

    def test_existing_escape_cycle_and_non_directory_rejections_remain(self):
        root = "host-install"
        cases = [
            ([directory(root), directory(root + "/bin"), link(root + "/bin/tool", "../../outside")],
             "Relative symlink escaped archive root"),
            ([directory(root), directory(root + "/bin"), link(root + "/bin/tool", "other"),
              link(root + "/bin/other", "tool")], "Symlink cycle"),
            ([directory(root), directory(root + "/bin"), link(root + "/bin/tool", "real/child"),
              file(root + "/bin/real")], "Symlink traversed non-directory"),
            ([directory(root), link(root + "/tool", "/outside/private")],
             "Absolute symlink escaped original prefix"),
        ]
        for rows, reason in cases:
            with self.subTest(reason=reason):
                stderr = io.StringIO()
                with contextlib.redirect_stderr(stderr), self.assertRaisesRegex(RuntimeError, reason):
                    IMPORTER.validate_links(rows, root)
                self.assertEqual(stderr.getvalue(), "")

    def test_unsafe_sensitive_and_opaque_names_are_not_printed(self):
        values = ["/private/location", "other-root/bin/tool", "host-install/../private",
                  "host-install//tool", "host-install/bin/password", "host-install/bin/secret",
                  "actual-used-source-worktree/bin/" + "a" * 24, "host-install/bin/中文",
                  "host-install/bin/name\nnewline", "host-install/bin/back\\slash"]
        for value in values:
            with self.subTest(value=value):
                stderr = io.StringIO()
                with contextlib.redirect_stderr(stderr):
                    IMPORTER.report_missing_link_component(value, value)
                record = json.loads(stderr.getvalue().split(": ", 1)[1])
                for field in ("link", "missingComponent"):
                    self.assertEqual(record[field]["path"], "<withheld>")
                    self.assertTrue(record[field]["withheld"])
                    self.assertEqual(record[field]["characters"], len(value))
                self.assertNotIn(value, stderr.getvalue())

    def test_diagnostic_length_is_bounded_at_exact_path_limit(self):
        value = "host-install/" + "part/" * 47 + "abcdefgh"
        self.assertEqual(len(value), 256)
        for candidate, withheld in ((value, False), (value + "x", True)):
            with self.subTest(length=len(candidate)):
                stderr = io.StringIO()
                with contextlib.redirect_stderr(stderr):
                    IMPORTER.report_missing_link_component(candidate, candidate)
                record = json.loads(stderr.getvalue().split(": ", 1)[1])
                self.assertEqual(record["link"]["withheld"], withheld)
                self.assertLess(len(stderr.getvalue()), 1024)

    def test_broken_diagnostic_stream_preserves_original_rejection(self):
        class BrokenStream:
            def write(self, text):
                raise OSError("isolated test stream")
        rows = [directory("host-install"), link("host-install/tool", "missing")]
        with contextlib.redirect_stderr(BrokenStream()), self.assertRaisesRegex(
                RuntimeError, "^HOST_LLVM_IMPORT_REJECTED: Dangling symlink path component$"):
            IMPORTER.validate_links(rows, "host-install")


if __name__ == "__main__":
    unittest.main()
