import importlib.util
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v038_listen_screen as screen


def base_recipe():
    spec = importlib.util.spec_from_file_location('listen_screen_base104',
        REPO / 'desktop/tools/extract-upstream-listen-video-root.py')
    producer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(producer)
    return next(row for row in producer.RECIPES if row['originalPath'].endswith('/ListenVideoScreen.kt'))


class V038ListenScreenSourceTest(unittest.TestCase):
    def test_every_emitted_ui_source_has_an_exact_whole_original_inverse(self):
        originals = screen.fixed_sources(REPO)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            proof = screen.emit(REPO, root, base_recipe())
            self.assertEqual(3, len(proof['sources']))
            for receipt in proof['sources']:
                body = (root / receipt['output']).read_text(encoding='utf-8')
                for edit in reversed(receipt['edits']):
                    at = edit['offset']
                    self.assertEqual(edit['after'], body[at:at + len(edit['after'])])
                    body = body[:at] + edit['before'] + body[at + len(edit['after']):]
                if receipt['source'] == screen.NAV_PATH:
                    expected = screen.canonical_source(REPO, screen.NAV_PATH).read_text(encoding='utf-8')
                else:
                    expected = originals[Path(receipt['source']).name]
                self.assertEqual(expected.replace('\r\n', '\n'), body)
            self.assertFalse(proof['actualCollectionRouteMounted'])
            self.assertFalse(proof['mainCompiled'])

    def test_complete_latest_audio_and_favorite_search_are_the_only_canonical_navigation_changes(self):
        latest = screen.fixed_sources(REPO)['BiliPaiNavKey.kt']
        generated, edits, baseline = screen.adapt_navigation(REPO, latest)
        self.assertEqual(2, len(edits))
        self.assertEqual(screen.audio_declaration(latest), screen.audio_declaration(generated))
        strip = lambda text: text.replace(screen.audio_declaration(text), '').replace(
            screen.favorite_search.favorite_declaration(text), '')
        self.assertEqual(strip(baseline), strip(generated))

    def test_unreviewed_source_manifest_or_existing_platform_recipe_is_rejected(self):
        for name in (*screen.PINS, 'manifest.json'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                shutil.copytree(REPO / screen.ARCHIVE, root / screen.ARCHIVE)
                path = root / screen.ARCHIVE / name
                path.write_bytes(path.read_bytes() + b'\n')
                with self.assertRaises(ValueError): screen.fixed_sources(root)
        recipe = base_recipe()
        recipe['edits'] = recipe['edits'][:-1]
        with self.assertRaises(ValueError):
            screen.adapt_screen(screen.fixed_sources(REPO)['ListenVideoScreen.kt'], recipe)


if __name__ == '__main__': unittest.main()
