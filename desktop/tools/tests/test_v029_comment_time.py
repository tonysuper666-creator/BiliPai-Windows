from pathlib import Path
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import v029_comment_time as time_slice
from v025_source_paths import canonical_source


def tool(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), TOOLS / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class V029CommentTimeTest(unittest.TestCase):
    def test_selected_original_time_changes_reverse_to_the_complete_canonical_sources(self):
        for path, newest in time_slice.fixed_sources(REPO).items():
            original = canonical_source(REPO, path).read_text(encoding='utf-8')
            adapted, proof = time_slice.apply(REPO, path, original)
            restored = adapted
            for patch in reversed(proof['adaptations']):
                self.assertEqual(restored.count(patch['after']), 1)
                self.assertIn(patch['after'], newest)
                restored = restored.replace(patch['after'], patch['before'], 1)
            self.assertEqual(restored, original)
            self.assertTrue(proof['exactCompleteCanonicalInverse'])
            self.assertEqual(proof['fixedUpstreamCommit'], time_slice.COMMIT)
            # This slice must not pull unrelated charged-comment schema/UI into v025.
            self.assertNotIn('COMMENT_INLINE_CHARGED_BADGE_ID', adapted)

    def test_fixed_manifest_raw_bytes_and_git_blob_fail_closed(self):
        with tempfile.TemporaryDirectory(prefix='bp-comment-time-pins-') as temp:
            repo = Path(temp)
            archive = repo / time_slice.ARCHIVE
            shutil.copytree(REPO / time_slice.ARCHIVE, archive)
            manifest = archive / 'manifest.json'
            original = manifest.read_bytes()
            data = json.loads(original)
            data['fixedUpstreamCommit'] = '0' * 40
            manifest.write_text(json.dumps(data), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'manifest'):
                time_slice.fixed_sources(repo)
            manifest.write_bytes(original)
            source = archive / 'ReplyComponents.kt'
            source.write_bytes(source.read_bytes().replace(b'\n', b'\r\n'))
            with self.assertRaisesRegex(ValueError, 'raw bytes'):
                time_slice.fixed_sources(repo)
            data = json.loads(original)
            data['files'][0]['gitBlob'] = '0' * 40
            manifest.write_text(json.dumps(data), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'identity'):
                time_slice.fixed_sources(repo)

    def test_source_drift_is_rejected_before_a_time_consumer_is_emitted(self):
        path = time_slice.BASE + 'ReplyComponents.kt'
        original = canonical_source(REPO, path).read_text(encoding='utf-8')
        broken = original.replace('val metadataText = remember(item.ctime, displayLocation)', 'val changedMetadata = remember(item.ctime, displayLocation)')
        with self.assertRaises(ValueError):
            time_slice.apply(REPO, path, broken)

    def test_existing_producers_emit_the_same_preference_for_first_level_and_both_image_previews(self):
        with tempfile.TemporaryDirectory(prefix='bp-comment-time-generated-') as temp:
            output = Path(temp)
            editor = output / 'editor'
            reply = output / 'reply'
            tool('extract-upstream-dynamic-editor').generate(REPO, editor)
            tool('extract-upstream-dynamic-reply').generate(REPO, reply)
            def generated(root, name):
                matches = list(root.rglob(name))
                self.assertEqual(len(matches), 1, name)
                return matches[0].read_text(encoding='utf-8')
            policy = generated(editor, 'DesktopOriginalDynamicRichCommentPolicy.kt')
            first = generated(reply, 'DesktopOriginalReplyComponents.kt')
            thread = generated(reply, 'DesktopOriginalSubReplyDetailComponents.kt')
            saved = generated(reply, 'DesktopOriginalReplyCommentImageSpec.kt')
            self.assertIn('detailedTimeEnabled: Boolean = false', policy)
            self.assertIn('timeText = FormatUtils.formatCommentTime(', policy)
            self.assertIn('detailedTimeEnabled = detailedTimeEnabled', policy)
            self.assertIn('remember(item.ctime, displayLocation, detailedCommentTimeEnabled)', first)
            self.assertIn('val platform = LocalDesktopCommentBindings.current\n    val detailedCommentTimeEnabled', first)
            for body in (first, thread):
                self.assertEqual(body.count('detailedTimeEnabled = detailedCommentTimeEnabled\n                                    )'), 1)
            # Export is deliberately independent of the display toggle, preserving the complete timestamp.
            self.assertIn('FormatUtils.formatPrecisePublishTime(', saved)
            self.assertNotIn('detailedTimeEnabled', saved)
            for report in (editor, reply):
                proof = json.loads((report / 'v029-comment-time-selection.json').read_text(encoding='utf-8'))
                for row in proof if isinstance(proof, list) else [proof]:
                    self.assertTrue(row['exactCompleteCanonicalInverse'])


if __name__ == '__main__':
    unittest.main()
