from pathlib import Path
import hashlib
import importlib.util
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO/'desktop/tools'))
import v043_home_native_cards as source
import v041_video_status_labels as model_producer


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), REPO/'desktop/tools'/name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class V043HomeNativeCardsSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.before = (REPO/'desktop/upstream-slices/v041-video-status-labels/ListModels.kt').read_bytes()
        cls.temporary = tempfile.TemporaryDirectory(prefix='bp-home-native-source-')
        cls.output = Path(cls.temporary.name)
        cls.page = load('extract-upstream-home-page.py')
        cls.page.generate(REPO, cls.output/'page')
        cls.protocols = load('extract-upstream-home-protocols.py')
        cls.protocols.generate(REPO, cls.output/'protocols')

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def copy_archive(self):
        temporary = tempfile.TemporaryDirectory(prefix='bp-home-native-mutated-')
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        shutil.copytree(REPO/source.ARCHIVE, root/source.ARCHIVE)
        return root

    def test_complete_model_replacement_keeps_the_fixed_predecessor_and_inverse(self):
        raw, receipt = source.advance_model(REPO, self.before)
        self.assertEqual('5cb726df0e15c1ffcd79f3722cf9ffae12ffe30a8f0fb784ad223419bb578d1a',
                         hashlib.sha256(raw).hexdigest())
        self.assertEqual(29374, len(raw))
        self.assertTrue(receipt['completeOriginalInverse'])
        old, restored = self.before.splitlines(keepends=True), raw.splitlines(keepends=True)
        for _, a, b, c, d, _, _ in reversed(source.REVIEWED_LIST_DELTAS):
            restored[c:d] = old[a:b]
        self.assertEqual(self.before, b''.join(restored))
        with self.assertRaisesRegex(ValueError, 'predecessor changed'):
            source.advance_model(REPO, self.before + b'\n')

    def test_changed_original_or_unlisted_archive_input_cannot_be_generated(self):
        root = self.copy_archive()
        p = root/source.ARCHIVE/'ListModels.kt'
        p.write_bytes(p.read_bytes() + b'\n')
        with self.assertRaisesRegex(ValueError, 'bytes changed'):
            source.advance_model(root, self.before)
        root = self.copy_archive()
        (root/source.ARCHIVE/'extra.kt').write_bytes(b'')
        with self.assertRaisesRegex(ValueError, 'archive leaves'):
            source.verified_raw(root)

    def test_all_twenty_original_test_bodies_and_git_blobs_are_preserved(self):
        files = source.verified_raw(REPO)
        tests = source.emit_tests(REPO)
        self.assertEqual(20, sum(receipt['cases'] for _, receipt in tests.values()))
        for row in source.PINS:
            raw = files[row['archiveFile']]
            self.assertEqual(row['gitBlob'], hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest())
        for name, count in source.TEST_CASES.items():
            result, receipt = next(v for k, v in tests.items() if k.endswith('/'+name))
            restored = result
            for edit in reversed(receipt['importEdits']):
                restored = restored.replace((edit['after']+'\n').encode(), (edit['before']+'\n').encode(), 1)
            self.assertEqual(files[name], restored)
            self.assertEqual(count, result.count(b'    @Test\n'))

    def test_real_home_producer_emits_whole_request_policy_and_all_click_identities(self):
        root = self.output/'page'
        request = root/'com/android/purebilibili/feature/home/HomeVideoClickRequest.kt'
        policy = root/'com/android/purebilibili/navigation/HomeVideoNavigationPolicy.kt'
        original = source.verified_raw(REPO)
        self.assertEqual(original[request.name], request.read_bytes())
        self.assertEqual(original[policy.name], policy.read_bytes())
        screen = (root/'com/android/purebilibili/feature/home/DesktopOriginalHomeScreen.kt').read_text(encoding='utf-8')
        category = (root/'com/android/purebilibili/feature/home/HomeCategoryPage.kt').read_text(encoding='utf-8')
        self.assertEqual(3, screen.count('HomeVideoClickRequest('))
        self.assertEqual(2, screen.count('episodeId = video.episodeId,'))
        self.assertEqual(1, screen.count('episodeId = item.episodeId,'))
        self.assertEqual(5, category.count('HomeVideoClickRequest('))
        self.assertEqual(5, category.count('episodeId = video.episodeId,'))
        wrapped = screen[screen.index('val wrappedOnVideoClick:'):screen.index('val onTodayWatchVideoClick:')]
        native = wrapped.index('if (request.episodeId > 0L ||')
        self.assertLess(native, wrapped.index('pendingFeedScrollAnchor = captureHomeFeedScrollAnchor('))
        self.assertIn('return@homeClick', wrapped[native:])

    def test_real_web_mobile_and_merged_protocols_preserve_native_admission_and_ad_filter(self):
        p = self.output/'protocols/com/android/purebilibili/data/repository/DesktopOriginalHomeVideoProtocol.kt'
        text = p.read_text(encoding='utf-8')
        self.assertEqual(2, text.count('?.filter { it.isSupportedRecommendation }'))
        self.assertEqual(2, text.count('feedResp.data?.item?.filter { it.adInfo == null }?.map { it.toVideoItem() }'))
        self.assertNotIn('?.filter { it.goto == "av" }', text)
        self.assertIn('private val SharedContentRepository = DesktopOriginalSharedContentRequests', text)

    def test_native_cards_do_not_offer_av_watch_later_preview_or_online_count(self):
        root = self.output/'page/com/android/purebilibili/feature/home'
        category = (root/'HomeCategoryPage.kt').read_text(encoding='utf-8')
        self.assertEqual(1, category.count('onWatchLater = if (isNativeNonVideoCard) null'))
        self.assertEqual(2, category.count('onLongClick = if (isNativeNonVideoCard) null'))
        self.assertEqual(2, category.count('showOnlineCount = showOnlineCount && !isNativeNonVideoCard,'))
        hero = (root/'components/HomeHeroCarousel.kt').read_text(encoding='utf-8')
        self.assertIn('video.bvid.isNotBlank() && video.cid > 0L', hero)
        # This is the existing native preview guard. Native models discard cid/aid.
        model = source.verified_raw(REPO)['ListModels.kt'].decode('utf-8')
        self.assertIn('cid = if (type == "av") playerArgs?.cid ?: 0L else 0L,', model)

    def test_sole_model_producer_checks_the_older_status_lineage_before_native_followup(self):
        files = model_producer.emit_models(REPO)
        raw, receipt = files['com/android/purebilibili/data/model/response/ListModels.kt']
        self.assertEqual(source.verified_raw(REPO)['ListModels.kt'], raw)
        predecessor = receipt['previousStatusModel']
        self.assertEqual('3da884027dae2c641ac4deb90f5149dc9462d1b5d786f8e7db547f16f94e75f1', predecessor['sha256'])
        self.assertTrue(predecessor['onlyReviewedStatusLabelHunks'] and predecessor['inverseRestoresBaseline'])

    def test_sole_producer_refuses_output_inside_native_original_archive(self):
        root = self.copy_archive()
        with self.assertRaisesRegex(ValueError, 'Output overlaps fixed source'):
            model_producer.generate(root, root/source.ARCHIVE/'generated', root/'tests-out')
        self.assertFalse((root/source.ARCHIVE/'generated').exists())

    def test_real_legacy_test_producer_preserves_all_cases_and_records_the_single_label_migration(self):
        files = model_producer.emit_tests(REPO)
        data, receipt = files['com/android/purebilibili/data/model/response/VideoStatusLabelsTest.kt']
        migration = receipt['expectationCompatibility']
        self.assertEqual(6, receipt['cases'])
        self.assertEqual(6, data.count(b'    @Test\n'))
        self.assertTrue(migration['onlyExpectedLabelsChanged'] and migration['completePreviousProducerInverse'])
        self.assertEqual(1, data.count(source.NATIVE_STATUS_EXPECTATION.encode()))
        self.assertIn(b'assertTrue(video.statusLabels.isEmpty())', data)
        restored = data.replace(migration['after'].encode(), migration['before'].encode(), 1)
        self.assertEqual(source.LEGACY_STATUS_EMITTED_SHA256, hashlib.sha256(restored).hexdigest())
        for edit in reversed(receipt['importEdits']):
            restored = restored.replace((edit['after']+'\n').encode(), (edit['before']+'\n').encode(), 1)
        self.assertEqual((REPO/model_producer.ARCHIVE/'VideoStatusLabelsTest.kt').read_bytes(), restored)

    def test_legacy_label_compatibility_rejects_changed_tests_and_leaves_other_tests_untouched(self):
        raw = (REPO/model_producer.ARCHIVE/'VideoStatusLabelsTest.kt').read_text(encoding='utf-8')
        rewritten, _ = model_producer.rewrite_test(source.LEGACY_STATUS_TEST, raw)
        with self.assertRaisesRegex(ValueError, 'predecessor changed'):
            source.compatible_legacy_status_test(REPO, source.LEGACY_STATUS_TEST,
                                                 (rewritten+'\n').encode(), {})
        untouched = b'unrelated-test'; receipt = {'mode': 'unrelated'}
        data, returned = source.compatible_legacy_status_test(REPO, 'unrelated.kt', untouched, receipt)
        self.assertIs(data, untouched)
        self.assertIs(returned, receipt)


if __name__ == '__main__':
    unittest.main()
