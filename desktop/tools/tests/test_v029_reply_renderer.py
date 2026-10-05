from pathlib import Path
import importlib.util
import json
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import v029_reply_renderer as renderer
from v025_source_paths import canonical_source
from v029_comment_time import apply as time_selection, fixed_sources
from v029_comment_search import charged_delta


def selected(path):
    body, _ = time_selection(REPO, path, canonical_source(REPO, path).read_text(encoding='utf8'))
    if path.endswith('/ReplyComponents.kt'):
        body, _ = charged_delta(REPO, body)
    return body


def producer(name):
    spec = importlib.util.spec_from_file_location(name, TOOLS / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class V029ReplyRendererTest(unittest.TestCase):
    def test_complete_fixed_renderers_and_exact_prior_body_inverse(self):
        for path, latest in fixed_sources(REPO).items():
            before = selected(path)
            after, proof = renderer.renderer(REPO, path, before)
            self.assertEqual(latest, after)
            self.assertEqual(before, renderer.inverse(after, proof['edits']))
            self.assertEqual(renderer.digest(latest), proof['outputSha256LF'])

    def test_unknown_input_and_changed_inverse_are_rejected(self):
        for path in renderer.INPUTS:
            before = selected(path)
            with self.assertRaisesRegex(ValueError, 'Unknown'):
                renderer.renderer(REPO, path, before + '// unexpected')
            after, proof = renderer.renderer(REPO, path, before)
            edit = next(e for e in proof['edits'] if e['after'])
            # Reverse every later edit first to reach this edit's own coordinates.
            index = proof['edits'].index(edit)
            stage = renderer.inverse(after, proof['edits'][index + 1:])
            broken = stage[:edit['offset']] + '!' + stage[edit['offset'] + 1:]
            with self.assertRaisesRegex(ValueError, 'inverse'):
                renderer.inverse(broken, proof['edits'][:index + 1])

    def test_existing_sole_generators_emit_updated_ui_and_navigation_without_duplicates(self):
        with tempfile.TemporaryDirectory(prefix='bp-v029-reply-renderer-') as temp:
            root = Path(temp)
            for name, directory in [('extract-upstream-dynamic-reply', 'reply'),
                                    ('extract-upstream-dynamic-editor', 'editor')]:
                producer(name).generate(REPO, root / directory)
            paths = list(root.rglob('*.kt'))
            def body(name):
                matches = [p for p in paths if p.name == name]
                self.assertEqual(1, len(matches))
                return matches[0].read_text(encoding='utf8')
            reply = body('DesktopOriginalReplyComponents.kt')
            thread = body('DesktopOriginalSubReplyDetailComponents.kt')
            policy = body('DesktopOriginalDynamicRichCommentPolicy.kt')
            self.assertIn('fun TopTag(modifier: Modifier = Modifier)', reply)
            self.assertNotIn('crossfade(!lightweightMode)', reply)
            self.assertNotIn('PlatformTextStyle(includeFontPadding = false)', reply)
            self.assertIn('commentTopPaddingDp', thread)
            self.assertNotIn('commentVerticalPaddingDp', thread)
            self.assertNotIn('contentToActionSpacingDp', thread)
            self.assertIn('BilibiliNavigationTargetParser.parse(appSchema)', policy)
            all_bodies = '\n'.join(p.read_text(encoding='utf8') for p in paths)
            self.assertEqual(1, all_bodies.count('internal fun resolveReplyContentUrlNavigationUrl('))
            proof = json.loads((root / 'editor/v029-reply-link-selection.json').read_text(encoding='utf8'))
            before, _ = time_selection(REPO, renderer.BASE + 'ReplyComponents.kt',
                canonical_source(REPO, renderer.BASE + 'ReplyComponents.kt').read_text(encoding='utf8'))
            after, _ = renderer.rich_link_policy(REPO, before)
            self.assertEqual(before, renderer.inverse(after, proof['edits']))


if __name__ == '__main__':
    unittest.main()
