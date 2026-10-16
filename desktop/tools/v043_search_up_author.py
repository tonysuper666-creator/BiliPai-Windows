"""Whole original v043 SearchModels and tests; called by its existing sole producer.

The retained v041 archive is immutable. Its entire SearchModels payload must match
the fixed predecessor; the one reviewed cleanupFields delta must reproduce the
entire v043 payload, and inverse replay must restore that predecessor exactly.
"""
from pathlib import Path
import hashlib
import json

COMMIT = '35e2fac8a3870a2227a323e19f29f1e93d7e591c'
ARCHIVE = 'desktop/upstream-slices/v043-search-up-author'
MANIFEST_SHA256 = '529056aad8f8510e991f88606924d3da3c54745bcad711df545be39a199146f8'
BASE_SHA256 = '121a06212ebe034ffb274d822dce5fd5a685113e8984ff5554ab6b3006a94a30'
PINS = [
    dict(path='core-data/src/main/java/com/android/purebilibili/data/model/response/SearchModels.kt',
         archiveFile='SearchModels.kt', bytes=21022,
         sha256='941c24c45ab415de3ae291209e1b945f7c714053386ebd838c6b02e181d8fb36',
         gitBlob='3d00eacbd42fba08e6e44b95a6537c9418c91fe8', referenceOnly=False),
    dict(path='core-data/src/test/java/com/android/purebilibili/data/model/response/SearchUpItemTest.kt',
         archiveFile='SearchUpItemTest.kt', bytes=992,
         sha256='a0f6dfb560912c828fb58be2d91130c4ea103523dcbbfffe7a63d9b71198e8a8',
         gitBlob='4841a3eea267be36a7829931ef7d59641d1e041e', referenceOnly=False),
]
BEFORE = '''    fun cleanupFields(): SearchUpItem {
        return this.copy(
            uname = cleanSearchText(uname),
            usign = cleanSearchText(usign),
            upic = normalizeSearchImageUrl(upic)
        )
    }
'''.encode('utf-8')
AFTER = '''    fun cleanupFields(): SearchUpItem {
        val cleanedName = cleanSearchText(uname)
        return this.copy(
            uname = cleanedName,
            usign = cleanSearchText(usign),
            upic = normalizeSearchImageUrl(upic),
            // UP 主搜索附带的视频可能省略作者信息，沿用所属 UP 主补齐。
            res = res?.map { video ->
                video.copy(
                    author = video.author.ifBlank { cleanedName },
                    mid = video.mid.takeIf { it > 0 } ?: mid
                )
            }
        )
    }
'''.encode('utf-8')
TEST_IMPORTS = ((b'import org.junit.Assert.assertEquals\n', b'import kotlin.test.assertEquals\n'),
                (b'import org.junit.Test\n', b'import kotlin.test.Test\n'))

def sha(raw): return hashlib.sha256(raw).hexdigest()
def need(condition, message):
    if not condition: raise ValueError(message)

def verified_raw(repo):
    root = Path(repo).resolve() / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing or linked v043 search archive')
    expected = {'manifest.json', *(row['archiveFile'] for row in PINS)}
    need({p.name for p in root.iterdir()} == expected, 'Unexpected v043 search archive leaves')
    for name in expected:
        need((root/name).is_file() and not (root/name).is_symlink(), 'Linked/non-file v043 input: ' + name)
    manifest_raw = (root/'manifest.json').read_bytes()
    need(sha(manifest_raw) == MANIFEST_SHA256, 'v043 search manifest pin changed')
    manifest = json.loads(manifest_raw)
    need((manifest['schemaVersion'], manifest['repository'], manifest['tag'], manifest['commit'], manifest['files']) ==
         (1, 'jay3-yy/BiliPai', 'v0.4.3', COMMIT, PINS), 'v043 search manifest identity changed')
    files = {}
    for row in PINS:
        raw = (root/row['archiveFile']).read_bytes()
        need(len(raw) == row['bytes'] and sha(raw) == row['sha256'], 'v043 search bytes changed: ' + row['archiveFile'])
        blob = hashlib.sha1(b'blob ' + str(len(raw)).encode('ascii') + b'\0' + raw).hexdigest()
        need(blob == row['gitBlob'], 'v043 search Git blob changed: ' + row['archiveFile'])
        files[row['archiveFile']] = raw
    return files

def advance_model(repo, before):
    need(sha(before) == BASE_SHA256, 'The complete v041 SearchModels predecessor changed')
    files = verified_raw(repo)
    after = files['SearchModels.kt']
    need(before.count(BEFORE) == 1 and after.count(AFTER) == 1, 'SearchUpItem cleanupFields anchor is not unique')
    need(before.replace(BEFORE, AFTER, 1) == after, 'Unreviewed changes outside SearchUpItem author inheritance')
    need(after.replace(AFTER, BEFORE, 1) == before, 'SearchUpItem inverse did not restore the entire predecessor')
    return after, dict(tag='v0.4.3', upstreamCommit=COMMIT, archive=ARCHIVE, manifestSha256=MANIFEST_SHA256,
        source=PINS[0]['path'], sha256=sha(after), bytes=len(after), predecessorSha256=BASE_SHA256,
        identicalToArchive=True, singleReviewedMethodDelta=True, completeOriginalInverse=True)

def emit_tests(repo):
    raw = verified_raw(repo)['SearchUpItemTest.kt']
    result = raw
    for before, after in TEST_IMPORTS:
        need(result.count(before) == 1, 'Original search test import changed')
        result = result.replace(before, after, 1)
    restored = result
    for before, after in reversed(TEST_IMPORTS):
        need(restored.count(after) == 1, 'Adapted search test import is not unique')
        restored = restored.replace(after, before, 1)
    need(restored == raw and raw.count(b'    @Test\n') == result.count(b'    @Test\n') == 2,
         'Original UP-search test bodies/count changed')
    need(b'org.junit' not in result, 'JUnit4 reference remains in UP-search tests')
    path = PINS[1]['path'].removeprefix('core-data/src/test/java/')
    return {path: (result, dict(mode='whole-v043-test-import-adaptation', upstreamCommit=COMMIT,
        source=PINS[1]['path'], sha256=sha(result), cases=2, completeOriginalInverse=True,
        importEdits=[dict(before=a.decode().strip(), after=b.decode().strip()) for a,b in TEST_IMPORTS]))}
