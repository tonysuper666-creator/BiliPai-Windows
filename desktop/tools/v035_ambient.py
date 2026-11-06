from pathlib import Path
from desktop_paths import long_path
import copy, hashlib, json
from v025_source_paths import canonical_source

ARCHIVE = Path(__file__).resolve().parent.parent / 'upstream-slices/v035-ambient'
EXPECTED_MANIFEST_SHA256 = 'a65ff067f2d406e5defb599ff7086517e50115439159e0cd593df8dc9a2208b7'
COMMIT = '46eaf277e83512061785be9759d1f07aba3c4839'

def wide(p):
    s = str(p.resolve())
    return long_path(s)

def entries():
    raw = wide(ARCHIVE / 'manifest.json').read_bytes()
    assert hashlib.sha256(raw).hexdigest() == EXPECTED_MANIFEST_SHA256
    manifest = json.loads(raw)
    assert manifest['upstreamCommit'] == COMMIT
    return {row['path']: row for row in manifest['entries'] if not row['referenceOnly']}

def upgraded_recipes(repo, historical):
    replacement = entries()
    result = [copy.deepcopy(replacement[row['source']]['recipe'] if row['source'] in replacement else row) for row in historical]
    assert set(replacement) == {row['source'] for row in result if row['source'] in replacement}
    return result

def read_ambient_original(repo, path):
    replacement = entries()
    if path not in replacement:
        return wide(canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n', '\n')
    row = replacement[path]
    raw = wide(ARCHIVE / row['archiveFile']).read_bytes()
    assert hashlib.sha256(raw).hexdigest() == row['rawSha256']
    text = raw.replace(b'\r\n', b'\n').decode('utf-8')
    assert hashlib.sha256(text.encode()).hexdigest() == row['sha256LF']
    return text
