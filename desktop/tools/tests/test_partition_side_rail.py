"""Run the real CLI and undo its complete receipt against the pinned source bytes."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
ROOT = TOOLS.parents[1]
sys.path.insert(0, str(TOOLS))
from desktop_refresh_adaptation import unbind_refresh_launch, unbind_refresh_lists
from v025_source_paths import canonical_source


class PartitionProducerTest(unittest.TestCase):
    def test_actual_cli_and_complete_inverse(self):
        with tempfile.TemporaryDirectory() as directory:
            command = [sys.executable, str(TOOLS / "extract-upstream-home-partition.py"),
                       "--repo", str(ROOT / ".legacy-root"), "--output", directory]
            result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            inventory = json.loads((Path(directory) / "partition-producer-inventory.json").read_text())
            self.assertTrue(inventory["completePartitionInverseByteEqual"])
            row = inventory["emitted"][0]
            emitted = (Path(directory) / row["path"]).read_text()
            self.assertEqual(row["sha256LF"], hashlib.sha256(emitted.encode()).hexdigest())
            restored = unbind_refresh_launch(unbind_refresh_lists(emitted))
            restored = restored.replace("import kotlinx.coroutines.flow.update\nimport kotlinx.coroutines.flow.map",
                                        "import kotlinx.coroutines.flow.update")
            for change in reversed(row["adaptations"]):
                self.assertEqual(change["count"], restored.count(change["after"]), change["after"])
                restored = restored.replace(change["after"], change["before"])
            original = canonical_source(ROOT / ".legacy-root", row["origin"]).read_bytes()
            self.assertEqual(original, restored.encode(), "Inverse must restore all original bytes")
            self.assertEqual(inventory["pins"][row["origin"]], hashlib.sha256(original).hexdigest())
            print(json.dumps({"argv": command, "cwd": str(ROOT), "exit": result.returncode,
                              "originalSha256": hashlib.sha256(original).hexdigest(),
                              "emittedSha256": row["sha256LF"], "inverseByteEqual": True}))


if __name__ == "__main__":
    unittest.main()
