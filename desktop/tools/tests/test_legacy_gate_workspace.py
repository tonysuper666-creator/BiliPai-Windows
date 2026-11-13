"""The migration gate reads old originals and live desktop behavior together."""
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from desktop_paths import long_path
from legacy_gate_workspace import _remove_tree, legacy_gate_workspace

LONG_SEGMENT = "d" * 55
LONG_DIRECTORY = "/".join([LONG_SEGMENT] * 4)
LONG_ORIGINAL = "app/" + LONG_DIRECTORY + "/Original.kt"
LONG_LIVE = "desktop/" + LONG_DIRECTORY + "/Live.kt"


class LegacyGateWorkspaceTest(unittest.TestCase):
    def test_originals_are_pinned_while_live_desktop_and_git_history_remain_visible(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary) / "repo"
            repo.mkdir()

            def git(*args):
                return subprocess.check_output(["git", "-C", str(repo), *args], text=True).strip()

            def write(name, body):
                path = repo / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(body, encoding="utf-8")

            git("init", "-q")
            write("app/Original.kt", "old original")
            write("examples/plugins/example.js", "old upstream example")
            write("desktop/Behavior.kt", "old behavior")
            write(".github/workflows/safety.yml", "old policy")
            git("add", ".")
            git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "old")
            old = git("rev-parse", "HEAD")
            write("app/Original.kt", "new upstream")
            write("examples/plugins/example.js", "new upstream example")
            write("desktop/upstream-core/legacy-root.commit", old + "\n")
            write("desktop/tools/v025-canonical-sources.json", json.dumps({"sourceRoots": ["app/"]}))
            git("add", ".")
            git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "new")
            write("desktop/Behavior.kt", "live behavior regression")
            write("desktop/third-party/libmpv/build/fixed-inputs.json", "tracked build recipe")
            write("desktop/build/generated/current.kt", "live generated output")
            write(".github/workflows/safety.yml", "live publication policy")
            write("desktop/build.gradle.kts", '// 迁移到直编\nval migratedUpstreamModules = listOf("app")\n')
            write("app/src/main/java/Current.kt", "live migrated source")
            read_text = Path.read_text

            def windows_read_text(path, encoding=None, errors=None):
                return read_text(path, encoding=encoding or "cp1252", errors=errors)

            # Windows Python 3.12 defaults to an ANSI codec. Reading a UTF-8
            # recipe must work regardless of that process default.
            with patch.object(Path, "read_text", windows_read_text), legacy_gate_workspace(repo) as view:
                self.assertEqual("old original", (view / "app/Original.kt").read_text())
                self.assertEqual("old upstream example", (view / "examples/plugins/example.js").read_text())
                self.assertEqual("live behavior regression", (view / "desktop/Behavior.kt").read_text())
                self.assertEqual("tracked build recipe", (view / "desktop/third-party/libmpv/build/fixed-inputs.json").read_text())
                self.assertEqual("live generated output", (view / "desktop/build/generated/current.kt").read_text())
                self.assertEqual("live publication policy", (view / ".github/workflows/safety.yml").read_text())
                self.assertEqual("live migrated source", (view / ".merged-root/app/src/main/java/Current.kt").read_text())
                root = subprocess.check_output(["git", "-C", str(view), "rev-parse", "--show-toplevel"], text=True).strip()
                self.assertEqual(view.resolve(), Path(root).resolve())
                self.assertEqual(b"old original", subprocess.check_output(
                    ["git", "-C", str(view), "show", old + ":app/Original.kt"]))
            self.assertFalse(view.exists())
            self.assertEqual("live generated output", (repo / "desktop/build/generated/current.kt").read_text())
            self.assertEqual("new upstream", (repo / "app/Original.kt").read_text())


class LegacyGateWorkspaceLongPathTest(unittest.TestCase):
    """The retained originals and the live desktop exceed MAX_PATH on Windows."""

    def test_long_originals_and_live_desktop_are_copied_read_and_cleaned_up(self):
        # The fixture itself needs the extended-length form to be removed.
        temporary = tempfile.mkdtemp()
        try:
            repo = Path(temporary) / "repo"
            repo.mkdir()

            def git(*args):
                return subprocess.check_output(["git", "-C", str(repo), *args], text=True).strip()

            def write(name, body):
                path = repo / name
                long_path(path.parent).mkdir(parents=True, exist_ok=True)
                long_path(path).write_text(body, encoding="utf-8")

            def read(name):
                return long_path(repo / name).read_text(encoding="utf-8")

            git("init", "-q")
            # The build sets this too; the worktree checkout of a long path needs it.
            git("config", "core.longpaths", "true")
            write(LONG_ORIGINAL, "old original bytes")
            write(LONG_LIVE, "old deep live stub")
            write("examples/plugins/example.js", "old upstream example")
            write("desktop/Behavior.kt", "old behavior")
            write(".github/workflows/safety.yml", "old policy")
            git("add", ".")
            git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "old")
            old = git("rev-parse", "HEAD")
            write(LONG_ORIGINAL, "new upstream")
            write("desktop/upstream-core/legacy-root.commit", old + "\n")
            git("add", ".")
            git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "new")
            write(LONG_LIVE, "live deep desktop regression")
            write("desktop/build/generated/current.kt", "live build sentinel")
            write(".github/workflows/safety.yml", "live publication policy")
            write("desktop/build.gradle.kts", '// 迁移到直编\nval migratedUpstreamModules = listOf("app")\n')
            write("app/src/main/java/Current.kt", "live migrated source")

            with legacy_gate_workspace(repo) as view:
                if os.name == "nt":
                    self.assertGreater(len(str(long_path(view / LONG_ORIGINAL))), 260)
                self.assertEqual("old original bytes", long_path(view / LONG_ORIGINAL).read_text(encoding="utf-8"))
                self.assertEqual("live deep desktop regression", long_path(view / LONG_LIVE).read_text(encoding="utf-8"))
                # The junction still reaches the live build, never a copied one.
                self.assertEqual("live build sentinel", (view / "desktop/build/generated/current.kt").read_text(encoding="utf-8"))
                self.assertEqual("live migrated source", (view / ".merged-root/app/src/main/java/Current.kt").read_text(encoding="utf-8"))
                root = subprocess.check_output(["git", "-C", str(view), "rev-parse", "--show-toplevel"], text=True).strip()
                self.assertEqual(view.resolve(), Path(root).resolve())
                self.assertEqual(b"old original bytes", subprocess.check_output(
                    ["git", "-C", str(view), "show", old + ":" + LONG_ORIGINAL]))
            self.assertFalse(view.exists())
            self.assertEqual("live build sentinel", read("desktop/build/generated/current.kt"))
            self.assertEqual(["generated"], sorted(path.name for path in (repo / "desktop/build").iterdir()))
            self.assertEqual("new upstream", read(LONG_ORIGINAL))
            self.assertNotIn("bilipai-gates-", subprocess.check_output(
                ["git", "-C", str(repo), "worktree", "list", "--porcelain"], text=True))
        finally:
            # Git objects are read-only; clear the bit before the long-path delete.
            def force(function, path, error):
                os.chmod(path, stat.S_IWRITE)
                function(path)

            shutil.rmtree(long_path(temporary), onexc=force)

    def test_recursive_cleanup_refuses_a_target_outside_the_read_point(self):
        with tempfile.TemporaryDirectory() as temporary:
            read_point = Path(temporary) / "read-point"
            outside = Path(temporary) / "outside"
            read_point.mkdir()
            outside.mkdir()
            (outside / "keep.txt").write_text("keep", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                _remove_tree(read_point, outside)
            self.assertEqual("keep", (outside / "keep.txt").read_text(encoding="utf-8"))

    @unittest.skipUnless(os.name == "nt", "junctions are a Windows reparse point")
    def test_recursive_cleanup_unlinks_a_junction_without_traversing_it(self):
        with tempfile.TemporaryDirectory() as temporary:
            read_point = Path(temporary) / "read-point"
            outside = Path(temporary) / "outside"
            read_point.mkdir()
            outside.mkdir()
            (outside / "keep.txt").write_text("keep", encoding="utf-8")
            link = read_point / "link"
            subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(outside)],
                           check=True, capture_output=True)
            _remove_tree(read_point, link)
            self.assertFalse(link.is_junction())
            self.assertEqual("keep", (outside / "keep.txt").read_text(encoding="utf-8"))


if __name__ == '__main__':
    unittest.main()
