from pathlib import Path
import importlib.util
import json
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO/'desktop/tools'))
import v046_danmaku_like as like


def load(name):
    spec = importlib.util.spec_from_file_location('danmaku_like_'+name.replace('-', '_'), REPO/'desktop/tools'/name)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


class OriginalDanmakuLikeSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(); cls.root = Path(cls.temp.name)
        cls.owner = load('extract-upstream-api.py')
        cls.owner.generate(REPO, cls.root/'api')
        cls.syntax = like.parser(REPO)
        cls.proof = json.loads((cls.root/'api/v046-danmaku-like-api-proof.json').read_bytes())
        cls.body = (cls.root/'api/com/android/purebilibili/core/network/DesktopUpstreamApi.kt').read_text(encoding='utf8')
        cls.current = cls.body.split('import okhttp3.ResponseBody\n\n', 1)[1].removesuffix('\n')
        cls.previous = like.restore(cls.current, cls.proof['retainedToCurrent'])

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_existing_fixed_raw_api_license_catalog_and_task_are_shared_without_duplicate_owner(self):
        sources, manifest = like.fixed(REPO)
        self.assertEqual('d6bf8b1655ae910999860bf13e65c8fcea5b345b', manifest['commit'])
        self.assertEqual(8, len(manifest['files']))
        self.assertIn(like.original_method(REPO), sources['ApiClient.kt'])
        catalog = json.loads((REPO/'desktop/tools/compatibility-source-inputs.json').read_bytes())
        rows = [v for v in catalog['inputs'] if v['localPath'] == like.ARCHIVE+'/ApiClient.kt']
        self.assertEqual(1, len(rows)); self.assertIn('v046-danmaku-like', rows[0]['features'])
        self.assertFalse(rows[0]['referenceOnly'])
        build = (REPO/'desktop/build.gradle.kts').read_text(encoding='utf8')
        first = build.index('val extractUpstreamApi by tasks.registering(')
        block = build[first:build.index('\n}', first)]
        self.assertEqual(1, block.count('inputs.file("tools/v046_danmaku_like.py")'))
        self.assertEqual(1, block.count('inputs.dir("upstream-slices/v046-favorite-editor")'))

    def test_actual_generator_keeps_complete_original_retrofit_declaration_and_exact_whole_inverse(self):
        first, last = like.method_span(self.current, self.syntax)
        self.assertEqual(like.original_method(REPO), self.current[first:last])
        self.assertEqual(like.AFTER_METHOD_SHA256, like.sha(self.current[first:last]))
        a, b = like.method_span(self.previous, self.syntax)
        self.assertEqual(like.BEFORE_METHOD_SHA256, like.sha(self.previous[a:b]))
        self.assertEqual(self.current, self.previous[:a]+like.original_method(REPO)+self.previous[b:])
        self.assertTrue(self.proof['completeOriginalMethod'])
        self.assertTrue(self.proof['existingRootRequestCsrfAndResultHandlingUnchanged'])

    def test_foreign_missing_duplicate_and_already_advanced_methods_fail_before_output_admission(self):
        with self.assertRaisesRegex(ValueError, 'predecessor changed'):
            like.advance_api(REPO, self.current)
        first, last = like.method_span(self.previous, self.syntax)
        old = self.previous[first:last]
        broken = self.previous[:first]+old.replace('"web_player"', '"foreign"')+self.previous[last:]
        for bad in [broken, self.previous[:first]+self.previous[last:], self.previous+'\n'+old]:
            with self.subTest(bad=bad[-50:]), self.assertRaises(ValueError):
                like.advance_api(REPO, bad)

    def test_source_fields_are_confined_to_like_and_other_original_operations_are_byte_identical(self):
        current_span = like.method_span(self.current, self.syntax)
        previous_span = like.method_span(self.previous, self.syntax)
        self.assertEqual(self.previous[:previous_span[0]], self.current[:current_span[0]])
        self.assertEqual(self.previous[previous_span[1]:], self.current[current_span[1]:])
        for name in ['polaris_app_id', 'polaris_platform', 'spmid', 'from_spmid', 'statistics']:
            self.assertIn('@retrofit2.http.Field("'+name+'")', like.original_method(REPO))
        self.assertEqual(1, self.current.count('suspend fun likeDanmaku('))

    def test_prior_favorite_and_subscription_receipts_still_restore_their_exact_outputs(self):
        editor = json.loads((self.root/'api/v046-favorite-editor-api-proof.json').read_bytes())
        subscription = json.loads((self.root/'api/v044-favorite-subscription-api-proof.json').read_bytes())
        before_editor = like.restore(self.previous, editor['retainedToCurrent'])
        before_subscription = like.restore(before_editor, subscription['retainedToCurrent'])
        self.assertNotIn('getFavFolderInfo(', before_editor)
        self.assertNotIn('suspend fun favoriteFolder(', before_subscription)
        self.assertNotIn('suspend fun unfavoriteFolder(', before_subscription)
        self.assertIn('getFavFolderInfo(', self.current)
        self.assertIn('suspend fun favoriteFolder(', self.current)

    def test_real_generation_repeats_and_overlap_rejection_preserves_all_raw_leaves(self):
        original = {p.name:p.read_bytes() for p in (REPO/like.ARCHIVE).iterdir()}
        before = {p.relative_to(self.root).as_posix():p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        self.owner.generate(REPO, self.root/'api')
        self.assertEqual(before, {p.relative_to(self.root).as_posix():p.read_bytes() for p in self.root.rglob('*') if p.is_file()})
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            self.owner.generate(REPO, REPO/like.ARCHIVE/'not-an-output')
        self.assertEqual(original, {p.name:p.read_bytes() for p in (REPO/like.ARCHIVE).iterdir()})

    def test_connected_api_and_actual_protocol_fixture_have_balanced_kotlin_delimiters(self):
        fixture = REPO/'desktop/src/test/kotlin/com/bilipai/desktop/data/DesktopDanmakuLikeProtocolTest.kt'
        text = fixture.read_text(encoding='utf8')
        self.assertEqual(8, text.count('@Test fun'))
        self.assertIn('DesktopOriginalDanmakuProtocol(api,', text)
        self.assertIn('repository.ownedHomeService(BilibiliApi::class.java', text)
        self.assertIn('repository.httpClient.cookieJar.loadForRequest(request.url)', text)
        for source in [self.body, text]:
            stack = []
            for token, first, _ in self.syntax.kotlin_tokens(source):
                if token in ['(', '[', '{']: stack.append(token)
                elif token in [')', ']', '}']:
                    self.assertTrue(stack, first)
                    self.assertEqual({')':'(', ']':'[', '}':'{'}[token], stack.pop(), first)
            self.assertEqual([], stack)


if __name__ == '__main__':
    unittest.main()
