"""The read point the extractor tests share must be the pin the build would compile.

`legacy_read_point.legacy_read_point` hands the producers' `--repo` tree to tests. Its
contract is `desktop/settings.gradle.kts`: the tree counts only while `<root>/.legacy-commit`
equals the commit named by `desktop/upstream-core/legacy-root.commit`. These cases hold
that contract on throwaway trees so a stale copy from an older pin cannot pass as the
build's input, and a missing input still names the exact commit to materialize.
"""
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import legacy_read_point as read_point

COMMIT = 'd9595dc75cfe8d5c7128cb76352a607953b8bab6'
OTHER = '0' * 40
PROBE = 'app/src/main/java/Example.kt'


class LegacyReadPointTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self._tmp.name)
        self.pin = self.repo / read_point.PIN
        self.pin.parent.mkdir(parents=True)
        self.pin.write_text('# comment naming the pin\n\n%s\n' % COMMIT, encoding='utf-8')
        self.root = self.repo / '.legacy-root'

    def tearDown(self):
        self._tmp.cleanup()

    def materialize(self, commit=COMMIT):
        probe = self.root / PROBE
        probe.parent.mkdir(parents=True, exist_ok=True)
        probe.write_text('original\n', encoding='utf-8')
        (self.root / read_point.MARKER).write_text(commit, encoding='utf-8')

    def test_the_pin_is_the_single_commit_line(self):
        self.assertEqual(COMMIT, read_point.pinned_commit(self.repo))

    def test_tree_materialized_from_the_pin_is_the_read_point(self):
        self.materialize()
        self.assertEqual(self.root.resolve(), read_point.legacy_read_point(self.repo, PROBE))

    def test_the_tree_itself_can_be_the_argument(self):
        self.materialize()
        self.assertEqual(self.root.resolve(), read_point.legacy_read_point(self.root, PROBE))

    def test_marker_from_another_pin_is_rejected(self):
        self.materialize(OTHER)
        with self.assertRaises(RuntimeError) as raised:
            read_point.legacy_read_point(self.repo, PROBE)
        message = str(raised.exception)
        self.assertIn(OTHER, message)
        self.assertIn(COMMIT, message)

    def test_missing_marker_is_rejected(self):
        self.materialize()
        (self.root / read_point.MARKER).unlink()
        with self.assertRaises(RuntimeError) as raised:
            read_point.legacy_read_point(self.repo, PROBE)
        self.assertIn(read_point.MARKER, str(raised.exception))

    def test_missing_tree_names_the_commit_to_materialize(self):
        self.pin.parent.mkdir(parents=True, exist_ok=True)
        with self.assertRaises(RuntimeError) as raised:
            read_point.legacy_read_point(self.repo, PROBE)
        self.assertIn(COMMIT, str(raised.exception))
        self.assertIn(read_point.MARKER, str(raised.exception))

    def test_missing_pin_fails_instead_of_guessing(self):
        self.pin.unlink()
        self.materialize()
        with self.assertRaises(RuntimeError) as raised:
            read_point.legacy_read_point(self.repo, PROBE)
        self.assertIn(read_point.PIN, str(raised.exception))


if __name__ == '__main__':
    unittest.main()
