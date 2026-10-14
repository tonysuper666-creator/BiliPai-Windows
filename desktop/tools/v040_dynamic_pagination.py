"""One complete fixed upstream pagination decision in the existing tabs producer."""
from pathlib import Path
import hashlib
import json
from v038_listen_viewmodel import block_end

COMMIT = '415d01ec5bc3ac82521a9ac47bcc449b8e202460'
ORIGIN = 'app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicScreenStatePolicy.kt'
ARCHIVE = 'desktop/upstream-slices/v040-dynamic-pagination'
MANIFEST_SHA256 = '76a0a3fbb9b8c46a0003511b23a86039fc0cf1668e7c06441f9542902ee9a955'
SOURCE_SHA256 = 'cf08da0f4a2332cea17de66dbc772c9a0789295fe9af1ab57092bbefaa6fc597'
SOURCE_BYTES = 26796
SOURCE_BLOB = '6e5fbd57926502b9f1c6c37c7766ecafa81dd645'
BASE_FUNCTION_SHA256_LF = '65dbf1fc1e2ffc3562e4efacc3875dc267d04ce58df2ad49e8d97308bd5d4b6e'

def sha(raw): return hashlib.sha256(raw).hexdigest()

def complete_function(body):
    marker = 'internal fun shouldLoadMoreDynamicFeed('
    if body.count(marker) != 1: raise ValueError('Original pagination decision changed')
    start = body.index(marker); opening = body.index('{', start)
    return body[start:block_end(body, opening)]

def compose_policy(repo, baseline):
    root = Path(repo) / ARCHIVE
    manifest_raw = (root / 'manifest.json').read_bytes()
    if sha(manifest_raw) != MANIFEST_SHA256: raise ValueError('Pagination manifest changed')
    manifest = json.loads(manifest_raw)
    raw = (root / 'DynamicScreenStatePolicy.kt').read_bytes()
    blob = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
    if (manifest['repository'], manifest['commit']) != ('jay3-yy/BiliPai', COMMIT):
        raise ValueError('Pagination upstream identity changed')
    if (len(raw), sha(raw), blob) != (SOURCE_BYTES, SOURCE_SHA256, SOURCE_BLOB):
        raise ValueError('Pagination source bytes changed')
    if len(manifest['files']) != 1 or manifest['files'][0] != dict(path=ORIGIN,
            archiveFile='DynamicScreenStatePolicy.kt',bytes=SOURCE_BYTES,sha256=SOURCE_SHA256,gitBlob=SOURCE_BLOB):
        raise ValueError('Pagination source inventory changed')
    latest = complete_function(raw.decode('utf-8').replace('\r\n', '\n'))
    before = complete_function(baseline)
    if sha(before.encode()) != BASE_FUNCTION_SHA256_LF:
        raise ValueError('Existing tabs pagination decision changed')
    body = baseline.replace(before, latest, 1)
    at = baseline.index(before)
    if body[:at] + before + body[at + len(latest):] != baseline:
        raise ValueError('Complete original decision inverse failed')
    return body, dict(upstreamCommit=COMMIT, source=ORIGIN,
        completeOriginalFunction=latest, completeOriginalFunctionSHA256LF=sha(latest.encode()),
        baselineRemainderUnchanged=True, wholeFunctionRetained=True,
        actualWindowsCallerVerified=False, mainCompiled=False, runtimeExecuted=False)
