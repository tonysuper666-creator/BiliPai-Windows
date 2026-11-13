"""Replay the real Home producer and invert every pinned adaptation, without editing inputs.

Run after Gradle has materialized .legacy-root:
python3 desktop/tools/tests/test_home_message_unread_sources.py --repo .legacy-root --receipt <json>
Behavior coverage belongs to DesktopHomeMessageUnreadIntegrationTest, not source assertions.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
spec = importlib.util.spec_from_file_location("home_unread_producer", TOOLS / "extract-upstream-home-viewmodel.py")
producer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(producer)


def verify(repo):
    adaptations = json.loads((TOOLS / "extract-upstream-home-viewmodel-adaptations.json").read_text())
    original = producer.read(producer._desktop_canonical_source(repo, adaptations["source"]))
    assert producer.sha(original) == adaptations["sha256LF"]
    lines = original.splitlines(keepends=True)
    prepared = list(lines)
    for operation in reversed(adaptations["operations"]):
        start, end = operation["originalStartLine"] - 1, operation["originalEndLineExclusive"] - 1
        assert "".join(prepared[start:end]) == operation["expected"]
        prepared[start:end] = operation["replacement"].splitlines(keepends=True)
    prepared = "".join(prepared)
    assert producer.sha(prepared) == adaptations["desiredSha256LF"]

    # Inverse by recorded offsets, including insertions and deletions. No fuzzy matching.
    cursor = 0
    shifted = 0
    inverse = []
    for operation in adaptations["operations"]:
        start, end = operation["originalStartLine"] - 1, operation["originalEndLineExclusive"] - 1
        assert start >= cursor
        offset = len("".join(lines[:start])) + shifted
        old, new = operation["expected"], operation["replacement"]
        assert prepared[offset:offset + len(new)] == new
        inverse.append((offset, new, old))
        shifted += len(new) - len(old)
        cursor = end
    recovered = prepared
    for offset, new, old in reversed(inverse):
        assert recovered[offset:offset + len(new)] == new
        recovered = recovered[:offset] + old + recovered[offset + len(new):]
    assert recovered == original

    with tempfile.TemporaryDirectory(prefix="home-unread-producer-") as output:
        receipt = producer.generate(repo, Path(output))
        target = Path(output) / adaptations["target"]
        emitted = target.read_bytes()
        assert hashlib.sha256(emitted).hexdigest() == receipt["vmDesiredSha256LF"]
        actual = TOOLS.parent / "build/generated/home-viewmodel" / adaptations["target"]
        assert actual.read_bytes() == emitted, "Gradle output differs from actual CLI producer"
    return dict(receipt, inverseOriginalSha256LF=producer.sha(recovered), inverseOperations=len(inverse),
                inverseExact=True, gradleOutputMatchesCli=True,
                consumedTarget="build/transition-patched/" + adaptations["target"])


class HomeMessageUnreadSourcesTest(unittest.TestCase):
    def test_producer_matches_gradle_and_adaptations_invert_exactly(self):
        # The generated gate runs in its legacy read point with the live build
        # linked in; direct discovery uses Gradle's materialized legacy tree.
        repo = TOOLS.parents[1]
        if repo.name != ".legacy-root":
            repo = repo / ".legacy-root"
        verify(repo)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    args = parser.parse_args()
    receipt = verify(args.repo)
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    args.receipt.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(receipt))
