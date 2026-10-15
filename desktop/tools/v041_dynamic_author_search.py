"""Whole v041 author-search UI, policy and original tests; existing dynamic owner stays sole. """
from pathlib import Path
import hashlib
import json

COMMIT = 'd18fb20e31adb11bbec9fae6dfce7d705d556217'
ARCHIVE = 'desktop/upstream-slices/v041-dynamic-author-search'
MANIFEST_SHA256 = '757b485c82bc63c61b7ebc8ca77eb735d3699f2e104b41a6fbd66f8cab407a17'
BASE = 'app/src/main/java/com/android/purebilibili/feature/dynamic/'
POLICY = BASE + 'DynamicAuthorSearchPolicy.kt'
DIALOG = BASE + 'components/DynamicAuthorSearchDialog.kt'
TEST = 'app/src/test/java/com/android/purebilibili/feature/dynamic/DynamicAuthorSearchPolicyTest.kt'
MAIN_PATHS = (POLICY, DIALOG)


def sha(raw): return hashlib.sha256(raw).hexdigest()


def reference_sources(repo):
    root = Path(repo) / ARCHIVE
    raw = (root / 'manifest.json').read_bytes()
    if sha(raw) != MANIFEST_SHA256: raise ValueError('v041 author-search manifest changed')
    manifest = json.loads(raw)
    if (manifest.get('repository'), manifest.get('tag'), manifest.get('commit')) != ('jay3-yy/BiliPai', 'v0.4.1', COMMIT):
        raise ValueError('v041 author-search source identity changed')
    expected = {BASE + 'DynamicScreen.kt', BASE + 'DynamicViewModel.kt', DIALOG}
    if {row['path'] for row in manifest['files']} != expected or len(manifest['files']) != 3:
        raise ValueError('v041 author-search reference closure changed')
    result = {}
    for row in manifest['files']:
        raw = (root / row['archiveFile']).read_bytes()
        blob = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        if (len(raw), sha(raw), blob) != (row['bytes'], row['sha256'], row['gitBlob']):
            raise ValueError('v041 author-search original bytes changed: ' + row['archiveFile'])
        result[row['path']] = raw.decode('utf-8').replace('\r\n', '\n')
    return result


def main_sources(repo):
    from v041_feed_images import author_policy
    return {POLICY: author_policy(repo), DIALOG: reference_sources(repo)[DIALOG]}


def original_test(repo):
    from v041_feed_images import original_regression_sources
    body = original_regression_sources(repo)[TEST]
    if body.count('@Test') != 3: raise ValueError('Whole original author-search tests changed')
    return body


def prepare_method(repo):
    from v038_listen_viewmodel import block_end
    body = reference_sources(repo)[BASE + 'DynamicViewModel.kt']
    marker = '    fun prepareAuthorSearch() {'
    if body.count(marker) != 1: raise ValueError('Original author-search preparation changed')
    start = body.index(marker)
    return body[start:block_end(body, body.index('{', start))]


def generate_main(repo, output, host):
    files = []
    for path, body in main_sources(repo).items():
        files.append(host.write(output, path, body, body))
    return files


def generate_tests(repo, output, host):
    body = original_test(repo)
    return [host.write(output, TEST, body, body)]
