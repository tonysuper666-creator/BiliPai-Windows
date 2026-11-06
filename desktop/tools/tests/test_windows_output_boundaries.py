"""Long-path normalization must preserve source and output ownership boundaries."""
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

import check_slice_symbol_closure as gate
import v035_story as story
from v025_source_paths import wide


class LongPathOwnershipTests(unittest.TestCase):
    def setUp(self):
        self.base = Path(tempfile.mkdtemp(prefix='bp-bound-'))
        self.addCleanup(shutil.rmtree, gate._long(self.base))
        self.root = self.base / ('a' * 90) / ('b' * 90) / ('c' * 90)
        wide(self.root).mkdir(parents=True)
        self.assertGreater(len(str(self.root)), 260)

    def test_relative_kotlin_accepts_plain_and_extended_roots_beyond_max_path(self):
        target = wide(self.root / 'nested/Owned.kt')
        target.parent.mkdir()
        target.write_bytes(b'package owned\nfun owned() = Unit\n')
        for root in (self.root, Path(gate._long(self.root))):
            with self.subTest(root=root):
                self.assertEqual({'nested/Owned.kt'}, gate._relative_kotlin(root))

    def test_relative_kotlin_rejects_a_source_outside_its_root(self):
        outside = self.base / 'Outside.kt'
        outside.write_bytes(b'package outside\n')
        with patch.object(gate, 'walk_sources', return_value=[Path(gate._long(outside))]):
            with self.assertRaises(ValueError):
                gate._relative_kotlin(self.root)
        self.assertEqual(b'package outside\n', outside.read_bytes())

    def test_story_models_emit_exact_bytes_beyond_max_path(self):
        for root in (self.root, wide(self.root)):
            with self.subTest(root=root):
                emitted = story.emit_story_models(root)
                self.assertEqual(2, len(emitted))
                for name, target in zip(('StoryFeedPolicy.kt', 'StoryModels.kt'), emitted):
                    original, _, projection = story.source(name)
                    self.assertEqual(wide(self.root / projection['output']), target)
                    self.assertEqual(original.encode(), target.read_bytes())

    def test_story_escape_in_second_model_rejects_both_writes(self):
        original_source = story.source
        outside = wide(self.root.parent / 'Outside.kt')
        outside.write_bytes(b'OUTSIDE')

        def escaping_source(name):
            original, record, projection = original_source(name)
            if name == 'StoryModels.kt':
                projection = dict(projection, output='../Outside.kt')
            return original, record, projection

        with patch.object(story, 'source', side_effect=escaping_source):
            with self.assertRaisesRegex(ValueError, 'escapes its owner'):
                story.emit_story_models(self.root)
        self.assertEqual(b'OUTSIDE', outside.read_bytes())
        self.assertEqual([], gate.walk_sources(self.root))
        self.assertFalse(wide(self.root / 'original-v035-story-direct-inverse.json').exists())


if __name__ == '__main__':
    unittest.main()
