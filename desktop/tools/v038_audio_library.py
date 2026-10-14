"""Fixed v0.3.8 library Loader in the existing audio producer, with original tests."""
from pathlib import Path
import hashlib
import json
import re

COMMIT = 'b69ce4c21bd1bc2f33c5a4f3076b571f9b6d0f14'
ARCHIVE = 'desktop/upstream-slices/v038-audio-library'
MANIFEST_SHA256 = 'b207e445a87518f319902da64f8f33723cb59a559327b453890ddc72487a1a6a'
PINS = {
    'ListenVideoLibraryDataSource.kt': ('98f1d47577991bab5f6fe721d141625ea1f044b1bbdd603220000a36800cfbf3', 'fa784ec155882469585e92fda427c65fb9b4be38', 10638),
    'ListenVideoLibraryLoaderTest.kt': ('c72ee99c2f0de779fc879d33492d127554f1144841bb7d0af2c9e80c30209954', 'da9c123fd7f5c3fa49320fe8299a5896f2778e51', 18203),
}
DEFERRED_VM_TESTS = (
    'new refresh cancels old generation and only latest result is published',
    'logged out refresh skips remote requests',
    'detail retry repeats the last playlist request',
    'switching between albums and artists does not restart active index',
)


def digest(raw): return hashlib.sha256(raw).hexdigest()


def fixed_sources(repo):
    root = repo / ARCHIVE
    manifest_raw = (root / 'manifest.json').read_bytes()
    if digest(manifest_raw) != MANIFEST_SHA256: raise ValueError('Audio library manifest changed')
    manifest = json.loads(manifest_raw)
    if manifest['commit'] != COMMIT or manifest['repository'] != 'jay3-yy/BiliPai':
        raise ValueError('Wrong audio library source identity')
    if {row['file'] for row in manifest['files']} != set(PINS): raise ValueError('Incomplete audio source inventory')
    result = {}
    for row in manifest['files']:
        raw = (root / row['file']).read_bytes()
        expected_sha, expected_blob, expected_bytes = PINS[row['file']]
        blob = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        if (digest(raw), blob, len(raw)) != (expected_sha, expected_blob, expected_bytes):
            raise ValueError('Audio library source bytes changed: ' + row['file'])
        if (row['sha256'], row['gitBlob'], row['bytes']) != PINS[row['file']]:
            raise ValueError('Audio library inventory differs')
        result[row['file']] = raw.decode('utf-8').replace('\r\n', '\n')
    return result


def adapt_legacy_index_callback(body):
    """Name the original VM's progress callback after the Loader gained a third callback.

    No new VM, scheduler or publication logic is introduced. The complete old
    original VM's owned adapter remains the current UI until its separate update.
    """
    before = 'loader.indexFolders(targets) { completed, total ->'
    after = 'loader.indexFolders(targets, onFolderIndexed = { completed, total ->'
    if body.count(before) != 1: raise ValueError('Legacy ListenVideo index call changed')
    start = body.index(before)
    end = body.index('\n            }\n', start) + len('\n            }')
    original_call = body[start:end]
    named_call = original_call.replace(before, after, 1) + ')'
    adapted = body[:start] + named_call + body[end:]
    inverse = adapted[:start] + original_call + adapted[start+len(named_call):]
    if inverse != body: raise ValueError('Legacy callback platform inverse failed')
    return adapted, dict(offset=start, before=original_call, after=named_call,
                         label='name original index progress callback for v038 Loader', completeInverse=True)


def emit_loader_tests(repo, output):
    full = fixed_sources(repo)['ListenVideoLibraryLoaderTest.kt']
    body = full
    edits = []
    spans = []
    for name in DEFERRED_VM_TESTS:
        marker = '    @Test\n    fun `' + name + '`'
        if body.count(marker) != 1: raise ValueError('Original library test selection changed')
        start = body.index(marker)
        close = re.search(r'^    }\n', body[start:], re.MULTILINE)
        if close is None: raise ValueError('Original library test end missing')
        end = start + close.end()
        selected = body[start:end]
        if 'ListenVideoViewModel(' not in selected: raise ValueError('Deferred test is not a VM test')
        spans.append((start, end, selected))
    for start, end, selected in sorted(spans, reverse=True):
        if body[start:end] != selected: raise ValueError('Original library test boundary changed')
        edits.append(dict(offset=start, before=selected, after='', label='defer complete VM test'))
        body = body[:start] + body[end:]
    for before, after in (
        ('import com.android.purebilibili.feature.audio.viewmodel.ListenVideoViewModel\n', ''),
        ('import org.junit.jupiter.api.AfterEach', 'import kotlin.test.AfterTest'),
        ('import org.junit.jupiter.api.BeforeEach', 'import kotlin.test.BeforeTest'),
        ('@BeforeEach', '@BeforeTest'), ('@AfterEach', '@AfterTest'),
        ('class ListenVideoLibraryLoaderTest {', 'class DesktopV038ListenVideoLibraryLoaderTest {'),
    ):
        if body.count(before) != 1: raise ValueError('Library test platform seam changed')
        i = body.index(before)
        edits.append(dict(offset=i, before=before, after=after, label='test framework/name port'))
        body = body[:i] + after + body[i+len(before):]
    if body.count('    @Test\n') != 11 or 'ListenVideoViewModel(' in body:
        raise ValueError('Wrong original pure Loader test cohort')
    inverse = body
    for edit in reversed(edits):
        i = edit['offset']
        if inverse[i:i+len(edit['after'])] != edit['after']: raise ValueError('Test inverse seam changed')
        inverse = inverse[:i] + edit['before'] + inverse[i+len(edit['after']):]
    if inverse != full: raise ValueError('Complete original Loader test inverse failed')
    test_root = output.parent / 'audio-library-original-tests'
    target = test_root / 'com/android/purebilibili/feature/audio/library/DesktopV038ListenVideoLibraryLoaderTest.kt'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(body, encoding='utf-8', newline='\n')
    proof = dict(commit=COMMIT, manifestSHA256=MANIFEST_SHA256, originalCases=15, pureLoaderCases=11,
                 deferredVmCases=list(DEFERRED_VM_TESTS), completeOriginalTestInverse=True,
                 originalSHA256LF=digest(full.encode()), adaptedSHA256LF=digest(body.encode()), adaptations=edits)
    (test_root / 'v038-audio-library-original-test-proof.json').write_text(
        json.dumps(proof, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    return proof
