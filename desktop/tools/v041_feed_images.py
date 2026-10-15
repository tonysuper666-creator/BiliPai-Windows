"""Exact v0.4.1 feed-image functions and author policy; canonical v025 remains intact."""
from pathlib import Path
import hashlib
import json
from v038_listen_viewmodel import block_end

COMMIT = 'd18fb20e31adb11bbec9fae6dfce7d705d556217'
ARCHIVE = 'desktop/upstream-slices/v041-rss-dynamic'
MANIFEST_SHA256 = 'e9bb35a0424c11b21a459f2433047202cc331dbeed7b9f2e3511ed9f1c2e7903'
FILES = [{'archiveFile': 'FeedDocumentParser.kt', 'bytes': 9795, 'gitBlob': '57669c25c28a31819ed7ea9a502ebc1dc9461e02', 'path': 'app/src/main/java/com/android/purebilibili/core/plugin/feed/FeedDocumentParser.kt', 'sha256': 'a04c2c343528bf38bf41d04ff9f0a0656b4e1eb52c6b6ff4d4b5c77d65bafabf'}, {'archiveFile': 'FeedHtmlParser.kt', 'bytes': 10255, 'gitBlob': 'a9c038ff553612f758d059f484dc7b47c54e592e', 'path': 'app/src/main/java/com/android/purebilibili/core/plugin/feed/FeedHtmlParser.kt', 'sha256': '1f3248f1d7a80719eecc78e03574408ecef6d123b139d5467f0498165279b523'}, {'archiveFile': 'DynamicAuthorSearchPolicy.kt', 'bytes': 660, 'gitBlob': 'f58f8e8981de09a6a5d656dfad7c1e24f3fe7150', 'path': 'app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicAuthorSearchPolicy.kt', 'sha256': 'a278bdf09ac6c1310732a5626ebc408049ce204091660b1edc6046234867e041'}, {'archiveFile': 'FeedDocumentParserTest.kt', 'bytes': 17600, 'gitBlob': 'e2c9e19453d705829d25488d8f338b19933e3f84', 'path': 'app/src/test/java/com/android/purebilibili/core/plugin/feed/FeedDocumentParserTest.kt', 'sha256': 'dd512e8fe0858e3ed9baa8313712bbf38fcf6257e081a0d0b2a16f925958f196'}, {'archiveFile': 'DynamicAuthorSearchPolicyTest.kt', 'bytes': 1178, 'gitBlob': '38044d60bfeb1668a743e31cf463327005445cab', 'path': 'app/src/test/java/com/android/purebilibili/feature/dynamic/DynamicAuthorSearchPolicyTest.kt', 'sha256': 'e3e7052fdc9fc21c2e80d3353e53b224c08f7025a48316712eb39dd4c5adb889'}]
MARKERS = {'app/src/main/java/com/android/purebilibili/core/plugin/feed/FeedDocumentParser.kt': 'private fun Element.mediaImageUrl(): String? {', 'app/src/main/java/com/android/purebilibili/core/plugin/feed/FeedHtmlParser.kt': 'fun parseFeedHtml('}
BASE_FUNCTION_SHA256_LF = {'app/src/main/java/com/android/purebilibili/core/plugin/feed/FeedDocumentParser.kt': '6a91cf5f995e1810c5c4dc3695dadc3d538f995cbf0259830fef4ac9264efab2', 'app/src/main/java/com/android/purebilibili/core/plugin/feed/FeedHtmlParser.kt': '6c9c14a6b21c7a52e58fc388b98499e1fa4932e5f6dce12a656a7954a5cb107e'}

def sha(raw): return hashlib.sha256(raw).hexdigest()

def verified_sources(repo):
    root = Path(repo) / ARCHIVE
    raw = (root / 'manifest.json').read_bytes()
    if sha(raw) != MANIFEST_SHA256: raise ValueError('v041 feed/dynamic manifest changed')
    manifest = json.loads(raw)
    if (manifest.get('repository'), manifest.get('tag'), manifest.get('commit'), manifest.get('files')) != ('jay3-yy/BiliPai', 'v0.4.1', COMMIT, FILES):
        raise ValueError('v041 feed/dynamic source identities changed')
    result = {}
    for row in FILES:
        raw = (root / row['archiveFile']).read_bytes()
        blob = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        if (len(raw), sha(raw), blob) != (row['bytes'], row['sha256'], row['gitBlob']):
            raise ValueError('v041 feed/dynamic source bytes changed: ' + row['archiveFile'])
        result[row['path']] = raw.decode('utf-8').replace('\r\n', '\n')
    return result

def whole_function(body, marker):
    if body.count(marker) != 1: raise ValueError('Complete feed function anchor changed')
    start = body.index(marker)
    return body[start:block_end(body, body.index('{', start))]

def compose_feed_source(repo, path, baseline):
    if path not in MARKERS: raise ValueError('Not an explicitly reviewed feed parser')
    latest = whole_function(verified_sources(repo)[path], MARKERS[path])
    before = whole_function(baseline, MARKERS[path])
    if sha(before.encode()) != BASE_FUNCTION_SHA256_LF[path]:
        raise ValueError('Canonical feed function changed: ' + path)
    at = baseline.index(before)
    selected = baseline[:at] + latest + baseline[at + len(before):]
    if selected[:at] + before + selected[at + len(latest):] != baseline:
        raise ValueError('Complete feed function inverse failed')
    return selected, dict(upstreamCommit=COMMIT, source=path,
        completeOriginalFunctionSHA256LF=sha(latest.encode()),
        baselineFunctionSHA256LF=sha(before.encode()),
        baselineRemainderUnchanged=True, wholeFunctionRetained=True,
        actualWindowsProducerWired=False, mainCompiled=False, runtimeExecuted=False)

def author_policy(repo):
    return verified_sources(repo)['app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicAuthorSearchPolicy.kt']

def original_regression_sources(repo):
    return {path: body for path, body in verified_sources(repo).items()
            if path.startswith('app/src/test/')}


def feed_image_regression_source(repo):
    path = 'app/src/test/java/com/android/purebilibili/core/plugin/feed/FeedDocumentParserTest.kt'
    original = original_regression_sources(repo)[path]
    selected = original
    edits = []
    for name, reason in (
        ('placeholder titles are queued for background resolution',
         'Background title-resolution admission is outside this feed-image slice.'),
        ('opml folders become groups and round trip through export',
         'Subscription group models/import/export are outside this feed-image slice.'),
    ):
        marker = '    @Test\n    fun `' + name + '`() {'
        if selected.count(marker) != 1: raise ValueError('Deferred complete original feed case changed')
        at = selected.index(marker)
        end = block_end(selected, selected.index('{', at))
        before = selected[at:end]
        edits.append(dict(offset=at, before=before, after='', originalCase=name, reason=reason))
        selected = selected[:at] + selected[end:]
    restored = selected
    for edit in reversed(edits):
        at = edit['offset']
        restored = restored[:at] + edit['before'] + restored[at:]
    if restored != original or original.count('@Test') != 19 or selected.count('@Test') != 17:
        raise ValueError('Complete original feed test inverse failed')
    return selected, dict(upstreamCommit=COMMIT, source=path,
        originalTests=19, emittedCompleteOriginalTests=17, deferredOriginalTests=2,
        edits=edits, completeOriginalInverse=True, remainingOriginalBytesUnchanged=True,
        jvmTestsExecuted=False, mainCompiled=False, runtimeExecuted=False)
