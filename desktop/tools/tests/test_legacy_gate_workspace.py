"""The migration gate reads old originals and live desktop behavior together."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from legacy_gate_workspace import legacy_gate_workspace


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
                path.write_text(body)

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
            with legacy_gate_workspace(repo) as view:
                self.assertEqual("old original", (view / "app/Original.kt").read_text())
                self.assertEqual("old upstream example", (view / "examples/plugins/example.js").read_text())
                self.assertEqual("live behavior regression", (view / "desktop/Behavior.kt").read_text())
                self.assertEqual("tracked build recipe", (view / "desktop/third-party/libmpv/build/fixed-inputs.json").read_text())
                self.assertEqual("live generated output", (view / "desktop/build/generated/current.kt").read_text())
                self.assertEqual("live publication policy", (view / ".github/workflows/safety.yml").read_text())
                root = subprocess.check_output(["git", "-C", str(view), "rev-parse", "--show-toplevel"], text=True).strip()
                self.assertEqual(view.resolve(), Path(root).resolve())
                self.assertEqual(b"old original", subprocess.check_output(
                    ["git", "-C", str(view), "show", old + ":app/Original.kt"]))
            self.assertFalse(view.exists())
            self.assertEqual("live generated output", (repo / "desktop/build/generated/current.kt").read_text())
            self.assertEqual("new upstream", (repo / "app/Original.kt").read_text())
