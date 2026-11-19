"""Only the message-share UserBasicInfo source-selection contract."""
import importlib.util
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, REPO / 'desktop/tools' / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


producer = load('user_basic_info_producer', 'extract-dynamic-message-share.py')
parser = load('user_basic_info_parser', 'sync-upstream.py')
appearance = load('user_basic_info_appearance', 'extract-appearance-platform.py')
RESOLVER = producer.BASE + 'feature/message/InboxUserInfoResolver.kt'
VIEW_MODEL = producer.BASE + 'feature/message/InboxViewModel.kt'
DECLARATION = 'data class UserBasicInfo(val mid: Long, val name: String, val face: String)\n'
PACKAGE = 'package com.android.purebilibili.feature.message\n'


class UserBasicInfoSourceTest(unittest.TestCase):
    def select(self, resolver, view_model):
        sources = {RESOLVER: resolver, VIEW_MODEL: view_model}
        with patch.object(producer, 'read', side_effect=lambda repo, path: sources[path]) as read:
            selected = producer.user_basic_info_source(REPO, parser)
        self.assertEqual([RESOLVER, VIEW_MODEL], [call.args[1] for call in read.call_args_list])
        return selected

    def test_current_registered_sources_select_resolver(self):
        path, source = producer.user_basic_info_source(REPO, parser)
        self.assertEqual(RESOLVER, path)
        self.assertEqual(producer.read(REPO, RESOLVER), source)
        self.assertEqual(DECLARATION, appearance.declarations(parser, source, ['UserBasicInfo']))

    def test_legacy_layout_selects_view_model(self):
        old_view_model = PACKAGE + DECLARATION + 'class InboxViewModel {}\n'
        self.assertEqual((VIEW_MODEL, old_view_model), self.select(
            PACKAGE + 'internal object InboxUserInfoResolver {}\n', old_view_model))

    def test_missing_declaration_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'found 0'):
            self.select(PACKAGE, PACKAGE + 'val info: UserBasicInfo? = null\n')

    def test_declaration_in_both_sources_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'found 2'):
            self.select(PACKAGE + DECLARATION, PACKAGE + DECLARATION)

    def test_duplicate_declarations_in_either_source_are_rejected(self):
        for resolver, view_model in ((DECLARATION * 2, ''), ('', DECLARATION * 2)):
            with self.subTest(resolver=bool(resolver)), self.assertRaisesRegex(ValueError, 'found 2'):
                self.select(PACKAGE + resolver, PACKAGE + view_model)

    def test_ambiguous_file_cannot_be_hidden_by_one_valid_file(self):
        with self.assertRaisesRegex(ValueError, 'found 3'):
            self.select(PACKAGE + DECLARATION, PACKAGE + DECLARATION * 2)

    def test_nested_declarations_comments_literals_and_references_are_not_candidates(self):
        decoys = '''package example
// class UserBasicInfo
/* object UserBasicInfo { /* nested comment */ } */
val text = "class UserBasicInfo {}"
val raw = """interface UserBasicInfo {}"""
class Outer { data class UserBasicInfo(val mid: Long) }
fun local() { class UserBasicInfo }
val reference: UserBasicInfo? = null
'''
        source = PACKAGE + DECLARATION
        self.assertEqual((RESOLVER, source), self.select(source, decoys))

    def test_changed_bytes_in_either_registered_source_are_rejected(self):
        # Use the real catalog and reader, including validation of the non-selected file.
        for changed in (RESOLVER, VIEW_MODEL):
            with self.subTest(path=changed), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                for path in (RESOLVER, VIEW_MODEL):
                    target = root / path
                    target.parent.mkdir(parents=True, exist_ok=True)
                    raw = (REPO / path).read_bytes()
                    target.write_bytes(raw + b'\n' if path == changed else raw)
                with self.assertRaisesRegex(ValueError, 'Canonical original source digest mismatch'):
                    producer.user_basic_info_source(root, parser)

    def test_generate_uses_selected_original_for_body_and_provenance(self):
        class StopAfterDeclaration(Exception):
            pass

        for selected in (RESOLVER, VIEW_MODEL):
            with self.subTest(path=selected):
                sources = {path: PACKAGE + (DECLARATION if path == selected else '')
                           for path in (RESOLVER, VIEW_MODEL)}
                write = Mock(side_effect=StopAfterDeclaration)
                host = SimpleNamespace(media_extractor=lambda repo: SimpleNamespace(
                    parser_for=lambda repo: parser), write=write)
                with patch.object(producer, 'load', side_effect=[host, appearance]), patch.object(
                        producer, 'read', side_effect=lambda repo, path: sources[path]):
                    with self.assertRaises(StopAfterDeclaration):
                        producer.generate(REPO, Path('unused-output'))
                write.assert_called_once_with(Path('unused-output'), selected, sources[selected],
                    PACKAGE + DECLARATION, 'DesktopOriginalMessageUserBasicInfo.kt')


if __name__ == '__main__':
    unittest.main()
