"""The gate read point never follows links out of the live repository."""
import hashlib
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

from legacy_gate_workspace import legacy_gate_workspace


def _git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args], text=True).strip()


def _write(path, body):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")


def _fixture(root):
    repo = root / "repo"
    repo.mkdir()
    _git(repo, "init", "-q")
    _write(repo / "app/Original.kt", "old original")
    _write(repo / "desktop/Behavior.kt", "behavior")
    _write(repo / ".github/workflows/safety.yml", "policy")
    _git(repo, "add", ".")
    _git(repo, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "old")
    _write(repo / "desktop/upstream-core/legacy-root.commit", _git(repo, "rev-parse", "HEAD") + "\n")
    _write(repo / "desktop/build.gradle.kts", 'val migratedUpstreamModules = listOf("app")\n')
    _write(repo / "app/src/main/java/Current.kt", "live migrated source")
    _git(repo, "add", ".")
    _git(repo, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "new")
    _write(repo / "desktop/build/generated/current.kt", "live generated output")
    outside = root / "outside"
    _write(outside / "secret.txt", "external target")
    return repo, outside


def _link(link, target):
    if os.name == "nt":  # a junction, which copytree treats as a plain directory
        subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(target)], check=True,
                       stdout=subprocess.DEVNULL)
    else:
        link.symlink_to(target, target_is_directory=True)


def _snapshot(directory):
    return {str(path.relative_to(directory)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(directory.rglob("*")) if path.is_file()}


def _unlink(link):
    if link.is_symlink():
        link.unlink()
    else:
        os.rmdir(link)


class LegacyGateWorkspaceLinkTest(unittest.TestCase):
    def assert_clean(self, repo):
        self.assertEqual([], [path.name for path in repo.parent.glob("bilipai-gates-*")])
        self.assertEqual(1, _git(repo, "worktree", "list", "--porcelain").count("worktree "))
        self.assertEqual("live generated output", (repo / "desktop/build/generated/current.kt").read_text())

    def assert_refused(self, location, loop=False):
        with tempfile.TemporaryDirectory() as temporary:
            repo, outside = _fixture(Path(temporary))
            link = repo / location
            link.parent.mkdir(parents=True, exist_ok=True)
            _link(link, link.parent if loop else outside)
            before = _snapshot(outside)
            try:
                with self.assertRaisesRegex(ValueError, "refusing to copy link"):
                    with legacy_gate_workspace(repo):
                        self.fail("workspace opened through an escaping link")
                self.assertEqual(before, _snapshot(outside))
                self.assertEqual(["secret.txt"], [path.name for path in outside.iterdir()])
                self.assert_clean(repo)
            finally:
                _unlink(link)

    def test_links_leaving_the_repository_are_refused_and_their_target_is_untouched(self):
        for location in ("desktop/linked", ".github/linked", "app/src/main/java/linked"):
            with self.subTest(location=location):
                self.assert_refused(location)

    def test_a_link_back_over_its_own_directory_is_refused(self):
        self.assert_refused("desktop/nested/loop", loop=True)

    def test_cleanup_does_not_depend_on_path_is_junction(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo, _ = _fixture(Path(temporary))
            # Python < 3.12 has no Path.is_junction.
            with patch.object(Path, "is_junction", side_effect=AttributeError("is_junction"), create=True):
                with legacy_gate_workspace(repo) as view:
                    self.assertEqual("live generated output", (view / "desktop/build/generated/current.kt").read_text())
                    self.assertEqual("live migrated source", (view / ".merged-root/app/src/main/java/Current.kt").read_text())
            self.assertFalse(view.exists())
            self.assert_clean(repo)

    def test_python_without_tarfile_filters_is_rejected_before_a_worktree_is_added(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo, _ = _fixture(Path(temporary))
            with patch.dict(tarfile.__dict__):
                del tarfile.data_filter
                with self.assertRaisesRegex(RuntimeError, "needs Python 3.12"):
                    with legacy_gate_workspace(repo):
                        self.fail("workspace opened without tarfile filters")
            self.assert_clean(repo)


if __name__ == "__main__":
    unittest.main()
