from pathlib import Path
import contextlib
import hashlib
import importlib.util
import io
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO/'desktop/tools'))
import v043_home_recommendation_badges as source
from v025_source_paths import canonical_source


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), REPO/'desktop/tools'/name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class V043HomeRecommendationBadgeSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='bp-home-badge-source-')
        cls.output = Path(cls.temporary.name)
        cls.full = load('extract-upstream-home-full-card.py')
        cls.page = load('extract-upstream-home-page.py')
        with contextlib.redirect_stdout(io.StringIO()):
            cls.full.generate(REPO, cls.output/'full')
            cls.page.generate(REPO, cls.output/'page')

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def copy_archive(self):
        temporary = tempfile.TemporaryDirectory(prefix='bp-badge-mutated-')
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        shutil.copytree(REPO/source.ARCHIVE, root/source.ARCHIVE)
        return root

    def test_complete_shared_sources_are_emitted_once_as_exact_original_git_blobs(self):
        emitted = source.emit_originals(REPO)
        self.assertEqual(3, len(emitted))
        for relative, (raw, proof) in emitted.items():
            target = self.output/'full/generated'/relative
            self.assertEqual(raw, target.read_bytes())
            self.assertEqual(1, len(list((self.output/'full/generated').rglob(target.name))))
            self.assertTrue(proof['identicalToArchive'])
        for row in source.PINS:
            raw = (REPO/source.ARCHIVE/row['archiveFile']).read_bytes()
            self.assertEqual(row['gitBlob'], hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest())

    def test_both_complete_predecessors_are_reversible_and_unreviewed_changes_fail(self):
        emitted = source.emit_originals(REPO)
        for relative, (raw, proof) in emitted.items():
            name = Path(relative).name
            if name not in source.PREDECESSORS:
                continue
            before = canonical_source(REPO, proof['source']).read_bytes().replace(b'\r\n', b'\n')
            self.assertTrue(proof['completeOriginalInverse'])
            old, restored = before.splitlines(keepends=True), raw.splitlines(keepends=True)
            for _, a, b, c, d, _, _ in reversed(source.REVIEWED_DELTAS[name]):
                restored[c:d] = old[a:b]
            self.assertEqual(before, b''.join(restored))
            with self.assertRaisesRegex(ValueError, 'predecessor changed'):
                source.advance_original(name, before+b'\n', raw)

    def test_changed_original_and_unlisted_archive_leaf_fail_closed(self):
        root = self.copy_archive()
        p = root/source.ARCHIVE/'VideoRecommendationBadge.kt'
        p.write_bytes(p.read_bytes()+b'\n')
        with self.assertRaisesRegex(ValueError, 'bytes changed'):
            source.verified_raw(root)
        root = self.copy_archive()
        (root/source.ARCHIVE/'unexpected.kt').write_bytes(b'')
        with self.assertRaisesRegex(ValueError, 'archive leaves'):
            source.verified_raw(root)

    def test_both_full_card_callers_use_original_leading_slots_and_capture_reason_and_follow(self):
        root = self.output/'full/generated/com/android/purebilibili/feature/home/components/cards'
        for name in ['VideoCard.kt', 'HomeStyleSingleColumnVideoCard.kt']:
            text = (root/name).read_text(encoding='utf-8')
            self.assertIn('nameLeadingContent = if (isFollowing || video.isFollowed || video.recommendationReason.isNotBlank())', text)
            self.assertEqual(1, text.count('VideoRecommendationBadge(video.recommendationReason)'))
            self.assertEqual(1, text.count('recommendationReason = video.recommendationReason,'))
            self.assertEqual(1, text.count('followed = isFollowing || video.isFollowed,'))
            self.assertNotIn('inlineTrailingContent = if (isFollowing)', text)
            self.assertIn('VideoStatusBadges(', text)

    def test_actual_story_producer_keeps_single_line_author_and_original_native_snapshot_fields(self):
        p = self.output/'page/com/android/purebilibili/feature/home/components/cards/StoryVideoCard.kt'
        text = p.read_text(encoding='utf-8')
        self.assertEqual(1, text.count('VideoRecommendationBadge(video.recommendationReason)'))
        self.assertEqual(1, text.count('if (video.isFollowed) VideoRecommendationBadge("已关注")'))
        self.assertEqual(1, text.count('recommendationReason = video.recommendationReason,'))
        self.assertEqual(1, text.count('followed = video.isFollowed,'))
        self.assertIn('maxLines = 1,\n                overflow = TextOverflow.Ellipsis,\n                metaMaxLines = Int.MAX_VALUE,', text)
        self.assertIn('nativeCardSnapshot.capture()', text)

    def test_standalone_mode_keeps_one_original_author_and_snapshot_output(self):
        output = self.output/'standalone'
        with contextlib.redirect_stdout(io.StringIO()):
            self.full.generate(REPO, output, True)
        for relative, (raw, _) in source.emit_originals(REPO).items():
            p = output/'generated'/relative
            self.assertEqual(raw, p.read_bytes())
            self.assertEqual(1, len(list(output.rglob(p.name))))

    def test_real_producers_reject_output_inside_fixed_badge_sources_before_writing(self):
        for producer in [self.full, self.page]:
            root = self.copy_archive()
            with self.assertRaisesRegex(ValueError, 'Output overlaps fixed badge source'):
                producer.generate(root, root/source.ARCHIVE/'generated')
            self.assertFalse((root/source.ARCHIVE/'generated').exists())

    def test_direct_originals_are_excluded_and_both_real_owners_declare_fixed_inputs(self):
        build = (REPO/'desktop/build.gradle.kts').read_text(encoding='utf-8')
        for path in source.REPLACED:
            self.assertIn('exclude(canonicalOriginalIdentity("'+path+'"))', build)
        self.assertIn('listOf("extractUpstreamHomeFullCard", "extractOriginalHomePage")', build)
        self.assertIn('inputs.file("tools/v043_home_recommendation_badges.py")', build)
        self.assertIn('inputs.dir("upstream-slices/v043-home-recommendation-badges")', build)


if __name__ == '__main__':
    unittest.main()
