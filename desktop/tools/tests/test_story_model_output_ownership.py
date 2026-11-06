"""The retained Story projection cannot replace an unrelated output owner."""
from pathlib import Path
import tempfile
import unittest

from v035_story import emit_story_models, source


class StoryModelOutputOwnershipTest(unittest.TestCase):
    def test_former_owned_copy_is_replaced_and_repeat_emission_is_exact(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original, record, projection = source('StoryFeedPolicy.kt')
            target = root / projection['output']
            target.parent.mkdir(parents=True)
            target.write_text('// GENERATED from ' + record['originalPath'] + '; do not edit.\n// old body\n', encoding='utf8')
            first = emit_story_models(root)
            self.assertEqual(original.encode(), target.read_bytes())
            before = {path: path.read_bytes() for path in first}
            self.assertEqual(first, emit_story_models(root))
            self.assertEqual(before, {path: path.read_bytes() for path in first})

    def test_each_unowned_sibling_blocks_both_writes_and_preserves_its_bytes(self):
        for name in ('StoryFeedPolicy.kt', 'StoryModels.kt'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                _, _, projection = source(name)
                target = root / projection['output']
                target.parent.mkdir(parents=True)
                target.write_bytes(b'// user file\r\n')
                with self.assertRaisesRegex(ValueError, 'unowned Story model'):
                    emit_story_models(root)
                self.assertEqual(b'// user file\r\n', target.read_bytes())
                self.assertEqual([target], list(root.rglob('*.kt')))


if __name__ == '__main__':
    unittest.main()
