"""v0.4.6 danmaku protocol hardening: strict protobuf parse, failed-segment count and segment invalidation.

Upstream ccb37f85 (v0.2.8, "danmaku segment parsing, cache invalidation and per-scope settings sync") made a truncated
or corrupt DmSegMobileReply a detectable event instead of a silently empty segment:

- DanmakuProto.parseReply reads the wire format strictly (invalid tags, truncated varints and truncated length
  delimited fields throw) and reports DanmakuSegReply.parseFailed; a valid empty body is not a failure.
- ParsedDanmaku.failedSegmentCount counts the segments that failed that parse (the carried v029-bas-filter and
  v030-up-danmaku DanmakuManager archives read it).
- DanmakuContentRepository.invalidateDanmakuSegment evicts exactly one cached segment whose bytes failed the parse, and
  getDanmakuSegment treats an empty body as a successful segment (null is reserved for fetch failures).

The five carried files are whole v0.4.6 files (archive desktop/upstream-slices/v046-danmaku-protocol). Their Windows
owners stay the existing producers:

- DanmakuProto.kt was a `direct` v0.2.5 file. It is now emitted byte for byte by extract-upstream-danmaku.py and the
  canonical copy is excluded in prepareUpstreamSources (decision R-T2), so the same top-level names are compiled once.
- The AdvancedDanmakuData.kt model head (the part the producer cuts before ParsedDanmaku) and the
  DanmakuContentRepository.kt object body replace their v0.2.5 predecessors inside extract-upstream-danmaku.py and
  extract-upstream-media.py. ParsedDanmaku itself is not compiled on Windows: extract-upstream-danmaku.py cuts the
  file before it, and the Windows parse result is the desktop-owned DanmakuDocument, which carries the same
  failedSegmentCount (DanmakuParser.kt of the desktop package).
- The v0.4.6 invalidateDanmakuSegment removes a segment by its two-field key, but the Windows download cache key also
  carries the authorization receipt (bind_offline_task_repository in extract-upstream-media.py). One extra pinned
  mapping there evicts the corrupt segment for every receipt, because the playback window loader that calls it has no
  download binding. It is a pure cache eviction: nothing is read, requested or admitted.
- DanmakuParser.kt is carried for the failed-segment reference only. The two blocks the Windows build extracts from it
  (the advanced parser functions and the font scale policy) are proved byte-identical to the v0.2.5 predecessor below
  and the producer fails closed if a later pin changes them.
- DanmakuProtoPolicyTest.kt is emitted with two hash-pinned edits: its simple name DanmakuParser is bound to the
  desktop parser by an explicit import (an explicit import outranks a same-package declaration), and the class carries
  the V046Original prefix so it cannot collide with a later emission of the vendored v0.2.5 suite.

Scope of roadmap slice dmk-protocol-hardening. Done: the three definers above, the desktop DanmakuWindowLoader and
DanmakuParser calling them (detect, count, evict from the loader cache, retry), and a desktop-owned density-curve bottom
layer (DesktopDanmakuDensityProgress, NOT wired). Decided: the 60 fps overlay cadence stays, with the evidence in
SwingDesktopDanmakuFramePlatform.

Behaviour of the desktop caller, stated so nothing is over-claimed:

- The window loader retries a segment whose fetch failed or whose bytes failed the strict parse after
  DanmakuWindowLoader.SEGMENT_RETRY_INTERVAL_MS (the upstream 15 s). A local offline session is never retried, as in
  upstream DanmakuManager (hasMissingSegments = !isLocalSegmentSession && ...): its files are read from disk, so a re-read
  only rebuilds the identical document. DanmakuParser.parseProtobufWindow also counts an exception raised after a
  successful wire parse as a failed segment, like upstream, and drops what that segment had contributed.
- The loader's default invalidateShared port is DanmakuRepository.invalidateDanmakuSegment, which evicts from a cache the
  playback path never fills (ApiDesktopDanmakuSource.segment calls the API directly; only the offline download service reads
  DanmakuRepository.getDanmakuSegments). It is a forward-compatible hook; only the loader's own cache eviction changes
  playback.

Side effect of carrying the v0.4.6 DanmakuContentRepository body, accepted as the upstream semantic and untouched by code:
getDanmakuSegment returns an empty segment body as a successful segment (null stays reserved for fetch failures). The
offline export (DownloadDanmakuAssetService.download from the v029-special-danmaku archive) writes every element of
getDanmakuSegments by index and fails only when no path exists at all. So an empty slot no longer shifts the later slot
numbers (a segment whose fetch failed is still dropped and still shifts them, as in upstream, and the download manager still
rejects that count through standardSegmentCount), and a video whose every standard segment is empty now yields a COMPLETED
danmaku asset of 0-byte files instead of a FAILED one ("未获取到弹幕"). DesktopDownloadManager.offlineDanmaku then finds no
readable file (readLocalSource keeps only files longer than 0 bytes) and returns null, so offline playback shows no danmaku.

Not done, proposed follow-up slices (listed here so the roadmap entry is not read as closed):

- Density fallback wiring (roadmap v026-danmaku-density-fallback-curve stays partial). Nothing calls
  DesktopDanmakuDensityProgress, so playback still publishes NO fallback heat curve when the pbp endpoint is empty or 404.
  Upstream wires the fallback in VideoPlaybackViewModel.loadDanmakuDensityFallback, which the Windows view model (a recipe
  over the v0.2.5 file) does not carry, and the owner seam getPbpProgressData(bvid, cid, aid) has neither the duration nor a
  danmaku source. The follow-up calls DesktopDanmakuDensityProgress.load where getPbpProgressData fails or returns no values
  and flips the zero-callers pin in the source test. Its MAX_SEGMENTS = 40 bound is a deliberate deviation: upstream
  downloads every segment of any duration.
- The like-state calibration (resolveDanmakuLikeResult, upstream 75b2eb62), which needs the v0.4.6 likeDanmaku through the
  list-menu protocol, a Result<Boolean> chain through the Windows bindings and the generated owner state; the
  v046-danmaku-like slice only carries the request fields.
- A measured decision on the overlay cadence (v039-danmaku-60fps-cadence): the achieved paint rate at 5120x2160 has not been
  measured, so the 60 fps cap is kept.

Nothing here is compiled or executed by the Python checks; the proofs say so.
"""
from pathlib import Path
import hashlib
import json
import os

from v025_source_paths import canonical_source
from v044_favorite_detail import inverse_proof

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
INTRODUCED_BY = 'ccb37f85a34b6986f7465e0e0fa0650a4c33e768'
ARCHIVE = 'desktop/upstream-slices/v046-danmaku-protocol'
MANIFEST_SHA256 = 'b6f83c56a1a917f63a08b232cdaad109d1c7b6bdac00f079908c4d79c8283bed'
LICENSE_SHA256 = '3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986'

ENGINE = 'danmaku-engine/src/main/java/com/android/purebilibili/danmaku/parser/'
CONTENT_PATH = 'core-data/src/main/java/com/android/purebilibili/data/repository/DanmakuContentRepository.kt'
TEST_PATH = 'danmaku-engine/src/test/java/com/android/purebilibili/danmaku/parser/DanmakuProtoPolicyTest.kt'

# leaf -> (upstream path, bytes, sha256 over the raw bytes, git blob)
LEAVES = {
    'DanmakuProto.kt': (ENGINE + 'DanmakuProto.kt', 25151,
                        '606cb541a39bbbae4ee7160bd3f6424693a4a535bb0e573e1ffec84e5747aac7',
                        'f9df3ff19f48f2a7bef6f524fbc4ce396e9956f6'),
    'AdvancedDanmakuData.kt': (ENGINE + 'AdvancedDanmakuData.kt', 6865,
                               'aa66437743880bcd04b2bc945330d6f44eee554a3d6d07e9aa087311a01aee28',
                               '1fd75aaa8e023d8be7c8d67a7d81cd6436143085'),
    'DanmakuContentRepository.kt': (CONTENT_PATH, 10793,
                                    'f7bc05f436b30213915193821cca5712e6a59f9fa32e76ca29fdcfef828fb214',
                                    '53c252b0b3452ba002ed90586f0464368f279db3'),
    'DanmakuParser.kt': (ENGINE + 'DanmakuParser.kt', 20865,
                         '2ea28407e5c7b333b2e7456f2507ad9c501ddd3db37f10afde4fb4f4d60abf59',
                         '54075f8746ee9ab76460c06a9de314d35556f4ae'),
    'DanmakuProtoPolicyTest.kt': (TEST_PATH, 9573,
                                  '91bab184191b73a46d02d500173a3c65bcc150854b664ff815a1fc0ff86c2854',
                                  'd665dc2aadb78d6edb8ac779cfa7e2b91843a45d'),
}

# The v0.2.5 predecessors (LF normalised, read through the digest-checked canonical locator).
PREDECESSORS = {
    'DanmakuProto.kt': (ENGINE + 'DanmakuProto.kt', 24058,
                        '0aaa18b6c1766a26602965bc8e6f14cc0819f3bfc35e49a320b27f0eaa667d03'),
    'AdvancedDanmakuData.kt': (ENGINE + 'AdvancedDanmakuData.kt', 6700,
                               '1266bba1f7970281c896b0346791189ed579cf3dc4f1ad13970b5e7c95c95f46'),
    'DanmakuContentRepository.kt': (CONTENT_PATH, 10325,
                                    'bc8f6583bdb33b654a0249c8fc3c013b3c0760628a48ba3151a70392ab9f8686'),
    'DanmakuParser.kt': (ENGINE + 'DanmakuParser.kt', None, None),
}

PROTO_OUTPUT = 'com/android/purebilibili/danmaku/parser/DanmakuProto.kt'
TEST_OUTPUT = 'com/android/purebilibili/danmaku/parser/V046OriginalDanmakuProtoPolicyTest.kt'
PROOF_DIRECTORY = 'v046-danmaku-protocol-proofs'
OUTPUT_MODE = 'complete-original-v046-danmaku-protocol'

# The definers this slice exists for. Each must occur exactly once in its carried file.
PARSE_FAILED = '        val parseFailed: Boolean = false\n'
FAILED_SEGMENT_COUNT = '    val failedSegmentCount: Int = 0\n'
INVALIDATE = '    fun invalidateDanmakuSegment(cid: Long, segmentIndex: Int) {\n'

# The two blocks extract-upstream-danmaku.py takes from DanmakuParser.kt.
PARSER_BEGIN = '    internal fun parseAdvancedDanmaku('
PARSER_END = '    private fun formatDanmakuTextWithCount('
FONT_SCALE_BEGIN = 'const val BILIBILI_STANDARD_DANMAKU_FONT_SIZE'

# Test adaptation: two counted edits.
TEST_IMPORT_BEFORE = 'import kotlin.test.Test\n'
TEST_IMPORT_AFTER = 'import com.bilipai.desktop.danmaku.DanmakuParser\nimport kotlin.test.Test\n'
TEST_CLASS_BEFORE = 'class DanmakuProtoPolicyTest {\n'
TEST_CLASS_AFTER = 'class V046OriginalDanmakuProtoPolicyTest {\n'
TEST_CASES = 5


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def wide(path):
    """Extended-length form on Windows, so a deep Gradle output path cannot reach MAX_PATH; unchanged elsewhere."""
    path = Path(path).absolute()
    if os.name != 'nt':
        return path
    text = str(path)
    prefix = chr(92) * 2 + '?' + chr(92)
    return Path(text if text.startswith(prefix) else prefix + text)


def fixed(repo):
    """{leaf: LF text} of the carried upstream files. Raises when the archive differs from its pins in any way."""
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing v046 danmaku protocol archive')
    need({p.name for p in root.iterdir()} == set(LEAVES) | {'manifest.json', 'LICENSE'},
         'v046 danmaku protocol leaves changed')
    raw = (root / 'manifest.json').read_bytes()
    need(sha(raw) == MANIFEST_SHA256, 'v046 danmaku protocol manifest changed')
    manifest = json.loads(raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'v046 danmaku protocol source identity changed')
    need(len(manifest['files']) == len(LEAVES) and {r['filename'] for r in manifest['files']} == set(LEAVES),
         'v046 danmaku protocol source set changed')
    sources = {}
    for row in manifest['files']:
        path, size, digest, blob = LEAVES[row['filename']]
        need((row['path'], row['bytes'], row['sha256'], row['gitBlob']) == (path, size, digest, blob),
             'v046 danmaku protocol manifest row changed: ' + row['filename'])
        leaf = root / row['filename']
        need(leaf.is_file() and not leaf.is_symlink(), 'Linked v046 danmaku protocol source: ' + row['filename'])
        body = leaf.read_bytes()
        need(len(body) == size and sha(body) == digest, 'v046 danmaku protocol bytes changed: ' + row['filename'])
        need(hashlib.sha1(b'blob ' + str(len(body)).encode() + b'\0' + body).hexdigest() == blob,
             'v046 danmaku protocol Git blob changed: ' + row['filename'])
        need(b'\r' not in body, 'v046 danmaku protocol source is not LF only: ' + row['filename'])
        sources[row['filename']] = body.decode('utf8')
    licence = root / 'LICENSE'
    need(licence.is_file() and not licence.is_symlink() and sha(licence.read_bytes()) == LICENSE_SHA256 ==
         manifest['license']['sha256'], 'v046 danmaku protocol license changed')
    return sources


def validate_outputs(repo, *outputs):
    """Call first in every producer that hooks this slice: archive intact and no output inside or around it."""
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps v046 danmaku protocol inputs')


def predecessor(repo, leaf):
    """The v0.2.5 text the producers carried before this slice, LF normalised and pinned."""
    path, size, digest = PREDECESSORS[leaf]
    text = canonical_source(Path(repo), path).read_bytes().replace(b'\r\n', b'\n')
    if digest is not None:
        need(len(text) == size and sha(text) == digest, 'v046 danmaku protocol predecessor changed: ' + leaf)
    return text.decode('utf8')


def parser_blocks(text):
    """The two selections extract-upstream-danmaku.py makes from DanmakuParser.kt."""
    need(text.count(PARSER_BEGIN) == 1 and text.count(PARSER_END) == 1 and text.count(FONT_SCALE_BEGIN) == 1,
         'Danmaku parser extraction boundary changed')
    advanced = text[text.index(PARSER_BEGIN):text.index(PARSER_END)]
    font = text[text.index(FONT_SCALE_BEGIN):]
    return advanced, font


def unchanged_parser_blocks(repo):
    """Prove the extracted advanced-parser and font-scale blocks did not change between v0.2.5 and v0.4.6."""
    carried = parser_blocks(fixed(repo)['DanmakuParser.kt'])
    before = parser_blocks(predecessor(repo, 'DanmakuParser.kt'))
    need(carried == before, 'The extracted danmaku parser blocks changed in v0.4.6; review the Windows extractor')
    return dict(advancedParserBlockSHA256=sha(carried[0]), fontScaleBlockSHA256=sha(carried[1]),
                byteIdenticalToV025=True)


def write_proof(output, leaf, proof):
    target = wide(Path(output) / PROOF_DIRECTORY / (leaf + '.json'))
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    return target


def _base_proof(leaf, before, after):
    path, size, digest, blob = LEAVES[leaf]
    return dict(upstreamCommit=COMMIT, introducedBy=INTRODUCED_BY, archive=ARCHIVE, sourceManifestSHA256=MANIFEST_SHA256,
                sourcePath=path, upstreamBlob=blob, inputBytes=size, inputSHA256=digest,
                predecessorSHA256LF=sha(before), outputSHA256LF=sha(after),
                retainedToCurrent=inverse_proof(before, after), mainCompiled=False, applicationExecuted=False)


def emit_proto(repo, output):
    """Write the whole v0.4.6 DanmakuProto.kt byte for byte under the producer output; returns its path."""
    sources = fixed(repo)
    body = sources['DanmakuProto.kt']
    before = predecessor(repo, 'DanmakuProto.kt')
    need(body.count(PARSE_FAILED) == 1, 'DanmakuSegReply.parseFailed definer changed')
    need(before.count('parseFailed') == 0, 'The v0.2.5 predecessor already names parseFailed')
    data = body.encode('utf8')
    target = wide(Path(output) / PROTO_OUTPUT)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    proof = _base_proof('DanmakuProto.kt', before, body)
    proof.update(output=PROTO_OUTPUT, mode=OUTPUT_MODE, wholeOriginalEmittedByteForByte=True,
                 emittedSHA256=sha(target.read_bytes()), canonicalCopyExcludedInPrepareUpstreamSources=True,
                 extractedParserBlocks=unchanged_parser_blocks(repo))
    need(proof['emittedSHA256'] == LEAVES['DanmakuProto.kt'][2], 'Emitted DanmakuProto bytes differ from the archive')
    write_proof(output, 'DanmakuProto.kt', proof)
    return target


def advance_model(repo, before):
    """Replace the v0.2.5 AdvancedDanmakuData.kt text by the v0.4.6 file. The producer still cuts before ParsedDanmaku."""
    sources = fixed(repo)
    path, size, digest = PREDECESSORS['AdvancedDanmakuData.kt']
    need(len(before.encode('utf8')) == size and sha(before) == digest, 'Advanced danmaku model predecessor changed')
    after = sources['AdvancedDanmakuData.kt']
    need(after.count(FAILED_SEGMENT_COUNT) == 1, 'ParsedDanmaku.failedSegmentCount definer changed')
    proof = _base_proof('AdvancedDanmakuData.kt', before, after)
    proof.update(mode='complete-original-v046-model-head',
                 note='ParsedDanmaku is cut off by the producer; the Windows parse result is DanmakuDocument.')
    return after, proof


def advance_content_repository(repo, before):
    """Replace the v0.2.5 DanmakuContentRepository.kt text by the v0.4.6 file (the media producer renames the object)."""
    sources = fixed(repo)
    path, size, digest = PREDECESSORS['DanmakuContentRepository.kt']
    need(len(before.encode('utf8')) == size and sha(before) == digest, 'Danmaku content repository predecessor changed')
    after = sources['DanmakuContentRepository.kt']
    need(after.count(INVALIDATE) == 1, 'DanmakuContentRepository.invalidateDanmakuSegment definer changed')
    need(before.count('invalidateDanmakuSegment') == 0, 'The v0.2.5 predecessor already names invalidateDanmakuSegment')
    proof = _base_proof('DanmakuContentRepository.kt', before, after)
    proof.update(mode='complete-original-v046-content-object',
                 note='An empty segment body is a successful segment; null stays reserved for fetch failures.')
    return after, proof


def adapt_test(text):
    """The two counted, hash-pinned edits of the carried regression suite. Returns (adapted text, edit records)."""
    need(text.count(TEST_IMPORT_BEFORE) == 1 and text.count(TEST_CLASS_BEFORE) == 1,
         'Danmaku proto test adaptation anchors changed')
    need(text.count('@Test\n') == TEST_CASES, 'Danmaku proto test case count changed')
    need(text.count('DanmakuParser.parseProtobuf(') == 2, 'Danmaku proto test no longer names the parser twice')
    edits, adapted = [], text
    for label, before, after in (
            ('Bind the simple name DanmakuParser to the Windows parser', TEST_IMPORT_BEFORE, TEST_IMPORT_AFTER),
            ('Keep the original suite apart from a later vendored emission', TEST_CLASS_BEFORE, TEST_CLASS_AFTER)):
        at = adapted.index(before)
        edits.append(dict(offset=at, before=before, after=after, count=1, label=label))
        adapted = adapted[:at] + after + adapted[at + len(before):]
    restored = adapted
    for edit in reversed(edits):
        at = edit['offset']
        need(restored[at:at + len(edit['after'])] == edit['after'], 'Danmaku proto test inverse boundary changed')
        restored = restored[:at] + edit['before'] + restored[at + len(edit['after']):]
    need(restored == text, 'Danmaku proto test is not recoverable')
    return adapted, edits


def original_tests(repo):
    """({output path: adapted suite text}, proof). The producers write it only when they have a test output."""
    text = fixed(repo)['DanmakuProtoPolicyTest.kt']
    adapted, edits = adapt_test(text)
    proof = dict(upstreamCommit=COMMIT, archive=ARCHIVE, sourceManifestSHA256=MANIFEST_SHA256,
                 sourcePath=TEST_PATH, upstreamBlob=LEAVES['DanmakuProtoPolicyTest.kt'][3],
                 inputSHA256=sha(text), outputSHA256=sha(adapted), output=TEST_OUTPUT, originalJvmCases=TEST_CASES,
                 edits=edits, fullInputInverseRecovered=True, jvmTestsExecuted=False)
    return {TEST_OUTPUT: adapted}, proof


def emit_tests(repo, test_output):
    """Write the adapted suite and its proof under the producer's test root."""
    files, proof = original_tests(repo)
    for relative, text in files.items():
        target = wide(Path(test_output) / relative)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(text.encode('utf8'))
    write_proof(test_output, 'DanmakuProtoPolicyTest.kt', proof)
    return [wide(Path(test_output) / relative) for relative in files]
