"""Exercise the migration as a file-changing tool, including data preservation."""
import copy
import hashlib
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest


TOOLS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("migrate_video_batch", TOOLS / "migrate-video-batch.py")
migration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(migration)
SOURCE = "app/src/main/java/com/android/purebilibili/feature/video/screen/Events.kt"
PATCH = "desktop/upstream-core/patches/" + SOURCE + ".patch"


class MigrationWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="video-migration-")
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name).resolve()
        self.source = self.repo / SOURCE
        self.source.parent.mkdir(parents=True)
        self.source.write_text("package example\n\nval manager = AndroidManager()\n", encoding="utf-8")
        self.build = self.repo / "desktop/build.gradle.kts"
        self.build.parent.mkdir()
        self.build.write_text('val migratedVideoFeaturePaths = setOf(\n    "existing.kt",\n)\n'
                              'val upstreamOwnedGeneratedCopies = setOf(\n    "keep.kt",\n)\n', encoding="utf-8")
        self.run_git("init", "-q")
        self.run_git("add", ".")
        self.run_git("-c", "user.name=Migration fixture", "-c", "user.email=fixture@example.invalid",
                     "commit", "-qm", "upstream fixture")
        self.run_git("tag", "v0.5.0")
        self.recipe = dict(schema="bilipai-video-migration-v1", id="fixture",
                           upstream=dict(tag="v0.5.0", commit=self.run_git("rev-parse", "HEAD").strip()),
                           files=[dict(source=SOURCE, sha256LF=hashlib.sha256(self.source.read_bytes()).hexdigest(),
                                       retire=["original-video-detail-holder-full/" + SOURCE.removeprefix("app/src/main/java/")],
                                       edits=[dict(before="AndroidManager()", after="desktopPort")])])

    def run_git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args], stderr=subprocess.PIPE).decode()

    def test_dry_run_apply_reapply_and_real_patch_consumption(self):
        before = self.build.read_bytes()
        changes, _ = migration.plan(self.repo, self.recipe)
        self.assertEqual(self.build.read_bytes(), before)
        self.assertFalse((self.repo / PATCH).exists())
        migration.apply(self.repo, changes)
        self.assertIn('"existing.kt"', self.build.read_text())
        self.assertIn('"keep.kt"', self.build.read_text())
        self.assertIn('"com/android/purebilibili/feature/video/screen/Events.kt"', self.build.read_text())
        again, _ = migration.plan(self.repo, self.recipe)
        self.assertTrue(all(old == new for old, new in again.values()))
        migration.apply(self.repo, again)
        self.run_git("apply", "--check", PATCH)
        self.run_git("apply", PATCH)
        self.assertEqual(self.source.read_text(), "package example\n\nval manager = desktopPort\n")

    def test_conflict_preserves_other_planned_files_and_existing_patch(self):
        patch = self.repo / PATCH
        patch.parent.mkdir(parents=True)
        patch.write_text("existing reviewed desktop adaptation\n")
        before = self.build.read_bytes()
        with self.assertRaises(migration.Conflict):
            migration.plan(self.repo, self.recipe)
        unedited = copy.deepcopy(self.recipe)
        unedited["files"][0]["edits"] = []
        with self.assertRaises(migration.Conflict):
            migration.plan(self.repo, unedited)
        self.assertEqual(self.build.read_bytes(), before)
        self.assertEqual(patch.read_text(), "existing reviewed desktop adaptation\n")
        patch.unlink()
        changes, _ = migration.plan(self.repo, self.recipe)
        self.build.write_bytes(before + b"// concurrent unrelated edit\n")
        with self.assertRaises(migration.Conflict):
            migration.apply(self.repo, changes)
        self.assertFalse(patch.exists())
        self.assertTrue(self.build.read_bytes().endswith(b"// concurrent unrelated edit\n"))

    def test_upstream_change_and_escaped_path_preserve_checkout(self):
        original_build = self.build.read_bytes()
        self.source.write_text("new upstream API\n")
        with self.assertRaises(migration.Conflict):
            migration.plan(self.repo, self.recipe)
        escaped = copy.deepcopy(self.recipe)
        escaped["files"][0]["source"] = "app/src/main/java/com/android/purebilibili/feature/video/../../outside.kt"
        with self.assertRaises(migration.Conflict):
            migration.plan(self.repo, escaped)
        self.assertEqual(self.build.read_bytes(), original_build)
        self.assertEqual(self.source.read_text(), "new upstream API\n")
        self.assertFalse((self.repo / PATCH).exists())


if __name__ == "__main__":
    unittest.main()
