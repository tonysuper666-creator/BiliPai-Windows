from pathlib import Path
import importlib.util
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v040_reply_medal as source
import v029_comment_search as earlier_protocol
import v029_reply_renderer as earlier_renderer
from v025_source_paths import canonical_source


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, REPO / 'desktop/tools' / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


class V040ReplyMedalSourceTest(unittest.TestCase):
    def previous(self):
        protocol = earlier_protocol.fixed_sources(REPO)
        renderer = earlier_renderer.fixed_sources(REPO)
        return dict(model=canonical_source(REPO,
            'app/src/main/java/com/android/purebilibili/data/model/response/ResponseModels.kt').read_text(encoding='utf-8'),
            grpc=next(v for p, v in protocol.items() if p.endswith('/CommentGrpcRepository.kt')),
            renderer=next(v for p, v in renderer.items() if p.endswith('/ReplyComponents.kt')))

    def test_every_complete_original_unit_is_retained_and_the_rest_of_its_owner_is_unchanged(self):
        newest = source.fixed_sources(REPO)
        for kind, before in self.previous().items():
            with self.subTest(kind=kind):
                generated, proof = source.advance(REPO, before, kind)
                self.assertEqual(source.selection(newest[kind], kind), source.selection(generated, kind))
                at = proof['offset']
                self.assertEqual(before, generated[:at] + proof['before'] + generated[at + len(proof['after']):])
                self.assertTrue(proof['wholeOriginalUnitRetained'])
                self.assertFalse(proof['runtimeExecuted'])

    def test_all_three_actual_existing_producers_emit_the_new_shared_model_decoder_and_ui(self):
        newest = source.fixed_sources(REPO)
        producers = [
            ('model', 'extract-upstream-api.py', 'ResponseModels.kt'),
            ('grpc', 'extract-upstream-dynamic-reply-protocol.py', 'DesktopDynamicCommentGrpc.kt'),
            ('renderer', 'extract-upstream-dynamic-reply.py', 'DesktopOriginalReplyComponents.kt'),
        ]
        with tempfile.TemporaryDirectory(prefix='bilipai-medal-actual-producers-') as tmp:
            for kind, filename, target_name in producers:
                output = Path(tmp) / kind
                module('actual_medal_producer_' + kind, filename).generate(REPO, output)
                targets = list(output.rglob(target_name))
                self.assertEqual(1, len(targets), kind)
                body = targets[0].read_text(encoding='utf-8')
                self.assertEqual(source.selection(newest[kind], kind), source.selection(body, kind))

    def test_unreviewed_previous_units_and_modified_original_archives_are_rejected(self):
        for kind, before in self.previous().items():
            old = source.selection(before, kind)
            changed = before.replace(old, old.replace('medal', 'changedMedal'), 1)
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                source.advance(REPO, changed, kind)
        for leaf in ('manifest.json', *(p['archiveFile'] for p in source.PINS.values())):
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                shutil.copytree(REPO / source.ARCHIVE, root / source.ARCHIVE)
                path = root / source.ARCHIVE / leaf
                path.write_bytes(path.read_bytes() + b'\n')
                with self.assertRaises(ValueError): source.fixed_sources(root)


if __name__ == '__main__': unittest.main()
