"""Source checks for the v0.4.6 danmaku protocol hardening slice (roadmap dmk-protocol-hardening).

Python only: nothing here compiles Kotlin or starts a JVM. The JVM regression classes are source only and are counted.
"""
from pathlib import Path
import hashlib
import importlib.util
import json
import re
import shutil
import sys
import tempfile
import textwrap
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v046_danmaku_protocol as proto
from v046_favorite_editor import restore

DESKTOP = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/danmaku'
JVM_TESTS = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/danmaku'
PROTO_RELATIVE = 'com/android/purebilibili/danmaku/parser/DanmakuProto.kt'


def load(name):
    spec = importlib.util.spec_from_file_location('dmk_protocol_' + name.replace('-', '_').replace('.', '_'),
                                                  REPO / 'desktop/tools' / name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def balanced(testcase, syntax, source, label):
    stack = []
    for token, first, _ in syntax.kotlin_tokens(source):
        if token in ['(', '[', '{']:
            stack.append(token)
        elif token in [')', ']', '}']:
            testcase.assertTrue(stack, '%s: unmatched %s at %d' % (label, token, first))
            testcase.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), '%s at %d' % (label, first))
    testcase.assertEqual([], stack, label)


class ProtocolSourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bp-dmk-')
        cls.root = Path(cls.temp.name)
        cls.danmaku = load('extract-upstream-danmaku.py')
        cls.media = load('extract-upstream-media.py')
        cls.sync = load('sync-upstream.py')
        cls.syntax = cls.media.parser_for(REPO)
        cls.danmaku.generate(REPO, cls.root / 'dm')
        cls.media.generate(REPO, cls.root / 'media', cls.root / 'tests')
        cls.sources = proto.fixed(REPO)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def archive_copy(self):
        temporary = tempfile.TemporaryDirectory(prefix='bp-dmk-input-')
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        shutil.copytree(REPO / proto.ARCHIVE, root / proto.ARCHIVE)
        return root

    # ----------------------------------------------------------------- archive integrity

    def test_every_leaf_the_manifest_the_license_and_the_leaf_set_are_pinned(self):
        self.assertEqual(set(proto.LEAVES), set(self.sources))
        for leaf in list(proto.LEAVES) + ['manifest.json', 'LICENSE']:
            with self.subTest(leaf=leaf):
                root = self.archive_copy()
                file = root / proto.ARCHIVE / leaf
                file.write_bytes(file.read_bytes() + b'\n')
                with self.assertRaises(ValueError):
                    proto.fixed(root)
        root = self.archive_copy()
        (root / proto.ARCHIVE / 'Extra.kt').write_bytes(b'package x\n')
        with self.assertRaisesRegex(ValueError, 'leaves changed'):
            proto.fixed(root)
        root = self.archive_copy()
        (root / proto.ARCHIVE / 'DanmakuProto.kt').unlink()
        with self.assertRaisesRegex(ValueError, 'leaves changed'):
            proto.fixed(root)

    def test_the_manifest_rows_are_the_real_v046_blobs_and_every_leaf_is_lf_only(self):
        manifest = json.loads((REPO / proto.ARCHIVE / 'manifest.json').read_bytes())
        self.assertEqual(('jay3-yy/BiliPai', 'v0.4.6', proto.COMMIT, 'raw'),
                         (manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']))
        for row in manifest['files']:
            raw = (REPO / proto.ARCHIVE / row['filename']).read_bytes()
            self.assertEqual(row['gitBlob'], hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest())
            self.assertNotIn(b'\r', raw)
            self.assertEqual(Path(row['path']).name, row['filename'])
        self.assertEqual(proto.MANIFEST_SHA256, hashlib.sha256((REPO / proto.ARCHIVE / 'manifest.json').read_bytes()).hexdigest())

    def test_the_three_definers_are_in_the_carried_files_exactly_once(self):
        self.assertEqual(1, self.sources['DanmakuProto.kt'].count(proto.PARSE_FAILED))
        self.assertEqual(1, self.sources['AdvancedDanmakuData.kt'].count(proto.FAILED_SEGMENT_COUNT))
        self.assertEqual(1, self.sources['DanmakuContentRepository.kt'].count(proto.INVALIDATE))
        # the strict wire parse the failed flag depends on
        for anchor in ('"Invalid protobuf tag"', '"Truncated varint"', '"Truncated protobuf bytes"', '"Varint overflow"'):
            self.assertEqual(1, self.sources['DanmakuProto.kt'].count(anchor), anchor)

    # ----------------------------------------------------------------- DanmakuProto emission

    def test_the_danmaku_producer_emits_the_whole_v046_proto_byte_for_byte(self):
        emitted = (self.root / 'dm' / PROTO_RELATIVE).read_bytes()
        self.assertEqual((REPO / proto.ARCHIVE / 'DanmakuProto.kt').read_bytes(), emitted)
        proof = json.loads((self.root / 'dm' / proto.PROOF_DIRECTORY / 'DanmakuProto.kt.json').read_bytes())
        self.assertTrue(proof['wholeOriginalEmittedByteForByte'] and proof['canonicalCopyExcludedInPrepareUpstreamSources'])
        self.assertEqual(proto.LEAVES['DanmakuProto.kt'][2], proof['emittedSHA256'])
        self.assertFalse(proof['mainCompiled'] or proof['applicationExecuted'])

    def test_the_v025_proto_is_recoverable_from_the_emitted_file_with_the_recorded_receipt(self):
        proof = json.loads((self.root / 'dm' / proto.PROOF_DIRECTORY / 'DanmakuProto.kt.json').read_bytes())
        emitted = (self.root / 'dm' / PROTO_RELATIVE).read_text(encoding='utf8')
        previous = restore(emitted, proof['retainedToCurrent'])
        self.assertEqual(proto.PREDECESSORS['DanmakuProto.kt'][2], proto.sha(previous))
        self.assertEqual(proto.predecessor(REPO, 'DanmakuProto.kt'), previous)
        self.assertNotIn('parseFailed', previous)
        self.assertIn('fun readTag(): Int = readVarint().toInt()', previous)
        with self.assertRaises(ValueError):
            restore(emitted + '\n', proof['retainedToCurrent'])

    def test_the_canonical_copy_is_excluded_exactly_once_and_the_name_is_declared_once(self):
        build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf8')
        line = ('exclude(canonicalOriginalIdentity("danmaku-engine/src/main/java/com/android/purebilibili/danmaku/parser/'
                'DanmakuProto.kt"))')
        self.assertEqual(1, build.count(line))
        block = build.split('val prepareUpstreamSources by tasks.registering', 1)[1].split('into(generatedUpstream)', 1)[0]
        self.assertEqual(1, block.count(line))
        # one declaration of the object over the producer output and every desktop-owned file
        declarations = [path for path in (self.root / 'dm').rglob('*.kt')
                        if re.search(r'(?m)^object DanmakuProto\b', path.read_text(encoding='utf8'))]
        self.assertEqual(1, len(declarations))
        owned = [path for path in (REPO / 'desktop/src').rglob('*.kt')
                 if re.search(r'(?m)^(?:internal |private |public )?object DanmakuProto\b', path.read_text(encoding='utf8'))]
        self.assertEqual([], owned)

    # ----------------------------------------------------------------- model head and content repository

    def test_the_model_head_is_the_v046_file_cut_before_parsed_danmaku(self):
        head = (self.root / 'dm/com/android/purebilibili/danmaku/parser/AdvancedDanmakuData.kt').read_text(encoding='utf8')
        carried = self.sources['AdvancedDanmakuData.kt']
        marker = '\n/**\n * 弹幕解析结果'
        self.assertEqual(1, carried.count(marker))
        self.assertTrue(head.endswith(carried.split(marker)[0].rstrip() + '\n'))
        self.assertNotIn('data class ParsedDanmaku', head)
        self.assertIn('import com.android.purebilibili.danmaku.parser.bas.BasDanmaku', head)
        self.assertIn('data class AdvancedDanmakuData(', head)
        proof = json.loads((self.root / 'dm' / proto.PROOF_DIRECTORY / 'AdvancedDanmakuData.kt.json').read_bytes())
        previous = restore(carried, proof['retainedToCurrent'])
        self.assertEqual(proto.predecessor(REPO, 'AdvancedDanmakuData.kt'), previous)

    def test_the_extracted_parser_blocks_are_unchanged_since_v025_and_the_check_fails_closed(self):
        proof = proto.unchanged_parser_blocks(REPO)
        self.assertTrue(proof['byteIdenticalToV025'])
        carried = self.sources['DanmakuParser.kt']
        advanced, font = proto.parser_blocks(carried)
        self.assertIn('internal fun parseAdvancedDanmaku(', advanced)
        self.assertIn('fun resolveBilibiliDanmakuFontScale(', font)
        for bad in (carried.replace(proto.PARSER_BEGIN, '    fun renamed(', 1), carried + proto.PARSER_END):
            with self.assertRaisesRegex(ValueError, 'extraction boundary changed'):
                proto.parser_blocks(bad)
        # the producer still emits both blocks from the same canonical text
        self.assertTrue((self.root / 'dm/com/android/purebilibili/danmaku/parser/DesktopAdvancedDanmakuParser.kt').is_file())
        self.assertTrue((self.root / 'dm/com/android/purebilibili/danmaku/parser/DesktopOriginalDanmakuFontScale.kt').is_file())

    def test_the_media_producer_emits_the_v046_object_body_with_exactly_the_pinned_bindings(self):
        repository = (self.root / 'media/com/android/purebilibili/data/repository/DesktopDownloadDanmakuRepository.kt').read_text(
            encoding='utf8').replace('\r\n', '\n')
        self.assertIn('// LF-normalized SHA-256: ' + proto.LEAVES['DanmakuContentRepository.kt'][2], repository)
        self.assertEqual(1, repository.count('fun invalidateDanmakuSegment(cid: Long, segmentIndex: Int) {'))
        self.assertEqual(1, repository.count('object DanmakuRepository {'))
        self.assertEqual(0, repository.count('if (bytes.isEmpty()) return@withContext null'))
        self.assertIn('A successful empty protobuf body is a valid segment with no comments.', repository)
        self.assertNotIn('object DanmakuContentRepository', repository)
        # The Windows segment cache key carries the authorization receipt: the v0.4.6 member must not build the two-field key
        # (a compile error no lexical gate can see), and every other construction already passes the receipt.
        self.assertEqual(['cid, segmentIndex, binding.cacheReceipt'],
                         re.findall(r'(?<!class )DanmakuSegmentCacheKey\(([^)]*)\)', repository))
        member = repository.split('fun invalidateDanmakuSegment(', 1)[1].split('\n    }\n', 1)[0]
        self.assertIn('if (entry.key.cid == cid && entry.key.segmentIndex == segmentIndex) {', member)
        self.assertNotIn('DanmakuSegmentCacheKey', member)
        # rebuild the expected object text from the archive, independent of the producer
        expected = self.sources['DanmakuContentRepository.kt']
        for original, binding in (
                ('import com.android.purebilibili.core.network.NetworkModule\n', ''),
                ('object DanmakuContentRepository {', 'object DanmakuRepository {'),
                ('    private val api = NetworkModule.api',
                 '    private val api get() = com.bilipai.desktop.download.DownloadDanmakuTransport.api')):
            self.assertEqual(1, expected.count(original))
            expected = expected.replace(original, binding, 1)
        account = self.media.read(REPO, self.media.BASE + 'data/repository/DanmakuRepository.kt')
        members = [textwrap.indent(self.media.function(account, name, self.syntax), '    ')
                   for name in ('getDanmakuView', 'getSpecialDanmakuSegments')]
        members.append('    fun clearDanmakuCache() = clearCache()')
        closing = expected.rfind('}')
        expected = expected[:closing] + '\n' + '\n\n'.join(members) + '\n}' + expected[closing + 1:]
        proof = json.loads((self.root / 'media/offline-task-binding-source-proof.json').read_bytes())
        unbound = repository
        for edit in reversed(proof['mappings']):
            self.assertEqual(edit['count'], unbound.count(edit['after']))
            unbound = unbound.replace(edit['after'], edit['before'], edit['count'])
        adapted = unbound.split('\n', 2)[2]
        download = textwrap.indent(self.media.function(unbound, 'downloadSpecialDanmaku', self.syntax), '    ')
        self.assertEqual(1, adapted.count(download))
        adapted = adapted.replace(download + '\n\n', '', 1)
        extra = '\n\nimport java.io.File\nimport kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.ensureActive'
        self.assertEqual(1, adapted.count(extra))
        self.assertEqual(expected.strip() + '\n', adapted.replace(extra, '', 1))
        self.assertEqual(proto.LEAVES['DanmakuContentRepository.kt'][2], proof['currentContentSha256LF'])
        self.assertEqual(proto.PREDECESSORS['DanmakuContentRepository.kt'][2], proof['canonicalContentSha256LF'])
        content = json.loads((self.root / 'media' / proto.PROOF_DIRECTORY / 'DanmakuContentRepository.kt.json').read_bytes())
        self.assertEqual(proto.PREDECESSORS['DanmakuContentRepository.kt'][2], content['predecessorSHA256LF'])
        self.assertEqual(proto.predecessor(REPO, 'DanmakuContentRepository.kt'),
                         restore(self.sources['DanmakuContentRepository.kt'], content['retainedToCurrent']))

    # ----------------------------------------------------------------- the emitted original suite

    def test_the_original_suite_is_emitted_with_two_pinned_edits_and_all_five_cases(self):
        files, proof = proto.original_tests(REPO)
        self.assertEqual([proto.TEST_OUTPUT], list(files))
        emitted = (self.root / 'tests' / proto.TEST_OUTPUT).read_bytes().decode('utf8')
        self.assertEqual(files[proto.TEST_OUTPUT], emitted)
        original = self.sources['DanmakuProtoPolicyTest.kt']
        self.assertEqual(5, original.count('@Test\n'))
        self.assertEqual(5, emitted.count('@Test\n'))
        self.assertEqual(2, len(proof['edits']))
        restored = emitted
        for edit in reversed(proof['edits']):
            at = edit['offset']
            self.assertEqual(edit['after'], restored[at:at + len(edit['after'])])
            restored = restored[:at] + edit['before'] + restored[at + len(edit['after']):]
        self.assertEqual(original, restored)
        self.assertIn('import com.bilipai.desktop.danmaku.DanmakuParser\n', emitted)
        self.assertIn('class V046OriginalDanmakuProtoPolicyTest {', emitted)
        self.assertNotIn('class DanmakuProtoPolicyTest', emitted)
        self.assertEqual(proof, json.loads((self.root / 'tests' / proto.PROOF_DIRECTORY / 'DanmakuProtoPolicyTest.kt.json').read_bytes()))
        balanced(self, self.syntax, emitted, 'emitted suite')

    def test_the_suite_binds_to_a_windows_parser_that_has_the_members_it_calls(self):
        owned = (DESKTOP / 'DanmakuParser.kt').read_text(encoding='utf8')
        self.assertIn('package com.bilipai.desktop.danmaku', owned)
        self.assertEqual(1, len(re.findall(r'(?m)^object DanmakuParser\b', owned)))
        self.assertEqual(1, owned.count('fun parseProtobuf(segments: List<ByteArray>): DanmakuDocument'))
        self.assertEqual(1, owned.count('val failedSegmentCount: Int = 0'))
        # the explicit import outranks the same-package metadata-only object the media producer emits
        metadata = (self.root / 'media/com/android/purebilibili/danmaku/parser/DesktopDanmakuMetadataParser.kt').read_text(encoding='utf8')
        self.assertIn('object DanmakuParser', metadata)
        self.assertNotIn('parseProtobuf', metadata)
        test = (self.root / 'tests' / proto.TEST_OUTPUT).read_text(encoding='utf8')
        self.assertEqual(2, test.count('DanmakuParser.parseProtobuf('))
        self.assertEqual(2, test.count('.failedSegmentCount'))

    # ----------------------------------------------------------------- fail closed

    def test_a_foreign_or_already_advanced_predecessor_is_rejected_before_any_output(self):
        carried = self.sources['AdvancedDanmakuData.kt']
        original = proto.predecessor(REPO, 'AdvancedDanmakuData.kt')
        with self.assertRaisesRegex(ValueError, 'model predecessor changed'):
            proto.advance_model(REPO, carried)
        with self.assertRaisesRegex(ValueError, 'model predecessor changed'):
            proto.advance_model(REPO, original + '\n')
        repository = proto.predecessor(REPO, 'DanmakuContentRepository.kt')
        with self.assertRaisesRegex(ValueError, 'content repository predecessor changed'):
            proto.advance_content_repository(REPO, self.sources['DanmakuContentRepository.kt'])
        with self.assertRaisesRegex(ValueError, 'content repository predecessor changed'):
            proto.advance_content_repository(REPO, repository.replace('MAX_SEGMENT_CACHE_COUNT = 12', 'MAX_SEGMENT_CACHE_COUNT = 13'))

    def test_a_changed_anchor_in_the_carried_files_is_rejected(self):
        for leaf, anchor, replacement, message in (
                ('DanmakuProto.kt', proto.PARSE_FAILED, '        val parseFailure: Boolean = false\n', 'bytes changed'),
                ('DanmakuContentRepository.kt', proto.INVALIDATE, '    fun invalidate(cid: Long, segmentIndex: Int) {\n', 'bytes changed')):
            with self.subTest(leaf=leaf):
                root = self.archive_copy()
                file = root / proto.ARCHIVE / leaf
                file.write_bytes(file.read_bytes().replace(anchor.encode(), replacement.encode(), 1))
                with self.assertRaisesRegex(ValueError, message):
                    proto.fixed(root)
        for bad, message in (
                (self.sources['DanmakuProtoPolicyTest.kt'].replace('class DanmakuProtoPolicyTest {', 'class Other {'), 'anchors changed'),
                (self.sources['DanmakuProtoPolicyTest.kt'].replace('@Test\n', '', 1), 'case count changed')):
            with self.assertRaisesRegex(ValueError, message):
                proto.adapt_test(bad)

    def test_producers_cannot_write_inside_or_around_the_archive_and_the_archive_stays_intact(self):
        before = {p.name: p.read_bytes() for p in (REPO / proto.ARCHIVE).iterdir()}
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            self.danmaku.generate(REPO, REPO / proto.ARCHIVE / 'child')
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            self.media.generate(REPO, self.root / 'ok', REPO / proto.ARCHIVE / 'tests')
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            proto.validate_outputs(REPO, REPO / 'desktop/upstream-slices')
        self.assertEqual(before, {p.name: p.read_bytes() for p in (REPO / proto.ARCHIVE).iterdir()})

    def test_generation_is_deterministic(self):
        again = Path(tempfile.mkdtemp(prefix='bp-dmk-again-'))
        self.addCleanup(shutil.rmtree, again, True)
        self.danmaku.generate(REPO, again / 'dm')
        for path in (self.root / 'dm').rglob('*'):
            if path.is_file():
                self.assertEqual(path.read_bytes(), (again / 'dm' / path.relative_to(self.root / 'dm')).read_bytes(), path.name)

    # ----------------------------------------------------------------- wiring

    def test_gradle_declares_the_generator_and_archive_for_both_producers(self):
        build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf8')
        block = build.split('tasks.matching { it.name in setOf("extractUpstreamDanmaku", "extractUpstreamMedia") }.configureEach {', 1)[1]
        block = block.split('\n}', 1)[0]
        self.assertEqual(1, block.count('inputs.file("tools/v046_danmaku_protocol.py")'))
        self.assertEqual(1, block.count('inputs.dir("upstream-slices/v046-danmaku-protocol")'))
        self.assertEqual(1, build.count('"upstream-slices/v046-danmaku-protocol"'))
        # the adapted suite goes to a root that is already a test source root, not under main
        self.assertIn('kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/live-stream-original-tests")) }', build)
        task = build.split('val extractUpstreamMedia by tasks.registering(Exec::class) {', 1)[1].split('\n}', 1)[0]
        self.assertIn('"--test-output", layout.buildDirectory.dir("generated/live-stream-original-tests")', task)
        self.assertIn('kotlin.srcDir(layout.buildDirectory.dir("generated/danmaku"))', build)

    def test_the_compatibility_inventory_pins_all_five_leaves_and_license_and_manifest(self):
        catalog = json.loads((REPO / 'desktop/tools/compatibility-source-inputs.json').read_bytes())
        self.assertIn(proto.ARCHIVE, catalog['buildInputRoots'])
        rows = [r for r in self.sync.adapted_compatibility_inputs(REPO) if r['localPath'].startswith(proto.ARCHIVE + '/')]
        self.assertEqual(sorted(proto.LEAVES), sorted(Path(r['localPath']).name for r in rows))
        self.assertEqual(['DanmakuParser.kt'], [Path(r['localPath']).name for r in rows if r['referenceOnly']])
        for row in rows:
            self.assertEqual(['v046-danmaku-protocol'], row['features'])
            self.assertEqual(proto.COMMIT, row['upstreamCommit'])
        archive = [a for a in catalog['archives'] if a['directory'] == proto.ARCHIVE]
        self.assertEqual(1, len(archive))
        self.assertEqual(sorted(proto.LEAVES) + ['LICENSE', 'manifest.json'],
                         sorted(Path(f['path']).name for f in archive[0]['files']))

    # ----------------------------------------------------------------- the desktop-owned Windows Kotlin

    def test_the_window_loader_keeps_the_trailing_lambda_contract_and_calls_the_hardened_members(self):
        text = (DESKTOP / 'DanmakuWindowLoader.kt').read_text(encoding='utf8')
        header = text.split('class DanmakuWindowLoader(', 1)[1].split(') : AutoCloseable', 1)[0]
        parameters = [p.strip().split(':', 1)[0].replace('private val ', '').replace('val ', '') for p in header.strip().split(',\n')]
        parameters = [p for p in parameters if p]
        # the duration lambda stays LAST so the overlay's trailing-lambda construction is unchanged
        self.assertEqual(['source', 'cid', 'aid', 'durationMs', 'indexingScope', 'invalidateShared', 'nowMs', 'standardDurationMs'],
                         [re.sub(r'\s*=.*', '', p).strip() for p in parameters])
        overlay = (DESKTOP / 'DanmakuOverlay.kt').read_text(encoding='utf8')
        self.assertEqual(1, overlay.count('DanmakuWindowLoader(playbackSource, cid, aid, (durationSeconds * 1000).toLong(),requests) {'))
        self.assertIn('DanmakuRepository::invalidateDanmakuSegment', header)
        self.assertEqual(1, text.count('DanmakuParser.parseProtobufWindow('))
        self.assertEqual(0, text.count('DanmakuParser.parseProtobuf('))
        self.assertEqual(1, text.count('failed.forEach(::invalidateSegment)'))
        self.assertEqual(1, text.count('const val SEGMENT_RETRY_INTERVAL_MS = 15_000L'))
        self.assertIn('failedSegments: List<Int> = emptyList()', text)
        self.assertIn('retryRevision:Long=0', text)
        # a corrupt-bytes retry never runs for the one complete XML document
        self.assertIn('noteSegmentOutcome(emptyList())', text)
        # nor for a local offline session (upstream DanmakuManager: hasMissingSegments = !isLocalSegmentSession && ...), and
        # such a session must not promise a retry in its warning
        self.assertEqual(1, text.count('private val localSegmentSession:Boolean get()=source.offlineSegmentCount!=null'))
        self.assertEqual(1, text.count('if (missing.isEmpty() || localSegmentSession) {'))
        self.assertEqual(1, text.count('if (localSegmentSession) "已丢弃。" else "已丢弃并将重试。"'))
        # the shared repository eviction is documented as the forward-compatible hook it is: playback never fills that cache
        self.assertIn('forward-compatible hook only', text)
        self.assertNotIn('evicted from this cache and from the shared repository cache', text)
        source = (DESKTOP / 'DesktopDanmakuSource.kt').read_text(encoding='utf8')
        self.assertEqual(1, source.count('return read { api.getDanmakuSeg(oid = cid, segmentIndex = index) }'))
        readers = [p.name for p in (REPO / 'desktop/src/main').rglob('*.kt')
                   if re.search(r'DanmakuRepository\s*\.\s*getDanmakuSegments?\s*\(', p.read_text(encoding='utf8'))]
        self.assertEqual([], readers)
        # the shared repository member exists in the compiled owner object
        repository = (self.root / 'media/com/android/purebilibili/data/repository/DesktopDownloadDanmakuRepository.kt').read_text(encoding='utf8')
        self.assertEqual(1, len(re.findall(r'(?m)^object DanmakuRepository \{', repository)))
        self.assertIn('import com.android.purebilibili.data.repository.DanmakuRepository', text)

    def test_the_parser_counts_failed_segments_and_the_density_layer_uses_only_existing_pieces(self):
        parser = (DESKTOP / 'DanmakuParser.kt').read_text(encoding='utf8')
        self.assertIn('data class DanmakuProtobufWindow(val document: DanmakuDocument, val failedPositions: List<Int>)', parser)
        self.assertEqual(1, parser.count('if (reply.parseFailed) {'))
        self.assertIn('failedSegmentCount = failedPositions.size', parser)
        # an exception after a successful wire parse also fails that segment (upstream try/catch per segment); a cancellation
        # propagates and the segment's partial contribution is dropped again
        self.assertIn('decode: (ByteArray) -> DanmakuProto.DanmakuSegReply = DanmakuProto::parseReply', parser)
        self.assertEqual(1, parser.count('val reply = decode(bytes)'))
        self.assertEqual(1, parser.count('if (failure is CancellationException) throw failure'))
        self.assertIn('import kotlinx.coroutines.CancellationException', parser)
        for restore_line in ('comments.subList(commentsBefore, comments.size).clear()',
                             'advanced.subList(advancedBefore, advanced.size).clear()',
                             'bas.subList(basBefore, bas.size).clear()',
                             'serverDisabled = serverDisabledBefore'):
            self.assertEqual(1, parser.count(restore_line), restore_line)
        # the only decoder reference is the strict upstream one, as the seam's default
        self.assertEqual(1, parser.count('DanmakuProto::parseReply'))
        self.assertNotIn('DanmakuProto.parseReply(', parser)
        density = (DESKTOP / 'DesktopDanmakuDensityProgress.kt').read_text(encoding='utf8')
        self.assertIn('import com.android.purebilibili.feature.video.progress.buildDanmakuDensityValues', density)
        controls = REPO / 'desktop/upstream-slices/v037-player-closure/PbpProgressPolicy.kt'
        self.assertEqual(1, controls.read_text(encoding='utf8').count('fun buildDanmakuDensityValues('))
        self.assertIn('fun buildPbpRidgeSamples(', controls.read_text(encoding='utf8'))
        # nothing calls the unwired bottom layer today: playback still has NO fallback heat curve. The KDoc, the generator
        # docstring and the gap text must say so, and must name the cap as the non-upstream bound it is. A wiring slice
        # replaces this pin.
        callers = [p for p in (REPO / 'desktop/src/main').rglob('*.kt')
                   if 'DesktopDanmakuDensityProgress' in p.read_text(encoding='utf8') and p.name != 'DesktopDanmakuDensityProgress.kt']
        self.assertEqual([], callers)
        self.assertIn('STATUS: a bottom layer only, NOT wired. Playback still has no fallback heat curve.', density)
        self.assertIn('Deliberate deviation from upstream, which downloads every segment of any duration', density)
        self.assertIn('const val MAX_SEGMENTS = 40', density)
        self.assertIn('loadDanmakuDensityFallback', density)
        # the premise of "not wired here": the Windows view model never carried the upstream method
        for tool in sorted((REPO / 'desktop/tools').glob('*.py')):
            if tool.name != 'v046_danmaku_protocol.py':
                self.assertNotIn('loadDanmakuDensityFallback', tool.read_text(encoding='utf8'), tool.name)
        docstring = Path(proto.__file__).read_text(encoding='utf8')
        self.assertIn('playback still publishes NO fallback heat curve', docstring)
        self.assertIn('MAX_SEGMENTS = 40 bound is a deliberate deviation', docstring)

    def test_the_new_and_changed_kotlin_files_have_balanced_delimiters_and_counted_cases(self):
        for path in (DESKTOP / 'DanmakuParser.kt', DESKTOP / 'DanmakuWindowLoader.kt', DESKTOP / 'DesktopDanmakuDensityProgress.kt',
                     DESKTOP / 'DesktopDanmakuFramePlatform.kt', JVM_TESTS / 'DanmakuWindowLoaderCorruptSegmentTest.kt',
                     JVM_TESTS / 'DesktopDanmakuDensityProgressTest.kt',
                     self.root / 'dm' / PROTO_RELATIVE):
            with self.subTest(path=path.name):
                text = path.read_text(encoding='utf8')
                self.assertNotIn('\r', text)
                balanced(self, self.syntax, text, path.name)
        # seven corrupt-segment cases, plus a failed fetch, an offline session, the default shared port and a parser exception
        self.assertEqual(11, (JVM_TESTS / 'DanmakuWindowLoaderCorruptSegmentTest.kt').read_text(encoding='utf8').count('@Test fun'))
        self.assertEqual(6, (JVM_TESTS / 'DesktopDanmakuDensityProgressTest.kt').read_text(encoding='utf8').count('@Test fun'))

    def test_every_new_top_level_name_has_one_owner_in_the_desktop_sources(self):
        names = {'DanmakuProtobufWindow': 'data class', 'DesktopDanmakuDensityProgress': 'object'}
        for name, kind in names.items():
            owners = [p for p in (REPO / 'desktop/src').rglob('*.kt')
                      if re.search(r'(?m)^(?:internal |public )?' + kind + r' ' + name + r'\b', p.read_text(encoding='utf8'))]
            self.assertEqual(1, len(owners), name)
            generated = [p for p in self.root.rglob('*.kt')
                         if re.search(r'(?m)^(?:internal |public )?' + kind + r' ' + name + r'\b', p.read_text(encoding='utf8'))]
            self.assertEqual([], generated, name)

    # ----------------------------------------------------------------- download side effect of the empty-segment semantic

    def test_the_empty_segment_download_side_effect_rests_on_the_carried_texts_and_is_documented(self):
        # v0.4.6 getDanmakuSegment returns an empty body as a successful segment: null is reserved for fetch failures
        content = self.sources['DanmakuContentRepository.kt']
        self.assertIn('Reserve null for fetch failures so playback can retry those segments.', content)
        self.assertEqual(1, content.count('getDanmakuSegment(cid, index)?.let { SegmentResult(index, it) }'))
        # the carried offline export indexes whatever list it gets, fails only when no path exists at all, and the reader
        # keeps only files longer than 0 bytes
        service = (REPO / 'desktop/upstream-slices/v029-special-danmaku/DownloadDanmakuAssetService.kt').read_text(
            encoding='utf8').replace('\r\n', '\n')
        self.assertEqual(1, service.count('val standardSegmentPaths = standardSegments.mapIndexed { index, bytes ->'))
        self.assertEqual(1, service.count('if (segmentPaths.isEmpty()) {'))
        self.assertEqual(1, service.count('errorMessage = "未获取到弹幕"'))
        self.assertEqual(1, service.count('File(path).let { it.isFile && it.length() > 0L }'))
        manager = (REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/download/DesktopDownloadManager.kt').read_text(encoding='utf8')
        self.assertEqual(1, manager.count('if (local.totalFileCount == 0) return null'))
        self.assertEqual(1, manager.count('if (manifest.standardSegmentCount != result.expectedStandardSegmentCount)'))
        # nothing about it was ever written down before this slice: the generator docstring now says it plainly
        docstring = ' '.join(Path(proto.__file__).read_text(encoding='utf8').split())
        for sentence in ('an empty slot no longer shifts the later slot numbers',
                         'a video whose every standard segment is empty now yields a COMPLETED danmaku asset of 0-byte files '
                         'instead of a FAILED one',
                         'a segment whose fetch failed is still dropped and still shifts them, as in upstream',
                         'readLocalSource keeps only files longer than 0 bytes) and returns null'):
            self.assertIn(sentence, docstring)

    # ----------------------------------------------------------------- cadence decision (v039-danmaku-60fps-cadence)

    def test_the_cadence_decision_rests_on_these_facts_and_changes_nothing(self):
        platform = (DESKTOP / 'DesktopDanmakuFramePlatform.kt').read_text(encoding='utf8')
        self.assertIn('Pacing decision (slice dmk-protocol-hardening', platform)
        # the product comment states the technical reasoning plainly: capped by construction, faster is unmeasured
        self.assertIn('hard-capped at 60 fps by construction', platform)
        self.assertIn('Whether a faster loop would actually be shown is unmeasured', platform)
        self.assertNotIn('no GUI may', platform)
        self.assertNotIn('while slices are authored', platform)
        self.assertIn('requestFrameCallback(callback: (Long) -> Unit, deadlineNanos: Long)', platform)
        self.assertEqual(1, platform.count('frameTimer.initialDelay ='))
        self.assertNotIn('refreshRate', platform.split('internal class SwingDesktopDanmakuFramePlatform', 1)[1])
        # the carried original class is the limiter: 1e9/60 ns, not adapted by Windows
        cadence = (REPO / 'desktop/upstream-slices/v038-danmaku-cadence/RenderFrameCadence.kt').read_text(encoding='utf8')
        self.assertIn('const val FRAME_INTERVAL_NS = 1_000_000_000L / 60L', cadence)
        self.assertIn('Absolute deadlines avoid dropping to 45fps on a 90Hz panel or drifting at 120Hz.', cadence)
        generator = (REPO / 'desktop/tools/v038_danmaku_cadence.py').read_text(encoding='utf8')
        self.assertEqual(1, generator.count('("    private var nextFrameNs = 0L", "    internal var nextFrameNs = 0L\\n        private set")'))
        self.assertIn('platform.requestFrameCallback(renderFrame, cadence.nextFrameNs)', generator)
        # the overlay is a single transparent Swing window repainted per frame
        overlay = (DESKTOP / 'DanmakuOverlay.kt').read_text(encoding='utf8')
        self.assertEqual(1, overlay.count('DesktopOriginalDanmakuFrameRequests(framePlatform, ::canRenderOverlay, ::tick)'))
        self.assertIn('overlay = JWindow(currentOwner)', overlay)
        # no desktop danmaku source derives a pacing interval from the monitor
        for path in DESKTOP.glob('*.kt'):
            text = path.read_text(encoding='utf8')
            self.assertNotIn('FRAME_INTERVAL_NS', text.replace('FRAME_INTERVAL_NS = 1_000_000_000L / 60L', ''), path.name)
            self.assertNotIn('getRefreshRate', text, path.name)


if __name__ == '__main__':
    unittest.main()
