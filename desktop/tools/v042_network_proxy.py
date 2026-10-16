"""Whole current proxy originals, through the existing Windows store and HTTP client."""
from pathlib import Path
import hashlib
import json

COMMIT = '5c34a82418b33bf88acea01784bb3c6b179d6491'
ARCHIVE = 'desktop/upstream-slices/v042-network-proxy'
MANIFEST_SHA256 = 'f46dacaced3991c49d14bae9ab8803e6c6b296f45c8a38c63560012dd295bdff'
APP = 'app/src/main/java/com/android/purebilibili/'
DATA = 'core-data/src/main/java/com/android/purebilibili/'
STORE = APP + 'core/store/NetworkProxyStore.kt'
POLICY = 'network-core/src/main/java/com/android/purebilibili/core/network/policy/NetworkProxyPolicy.kt'
API = APP + 'core/network/ApiClient.kt'
SECTIONS = APP + 'feature/settings/ui/SettingsSections.kt'
TEST = 'network-core/src/test/java/com/android/purebilibili/core/network/policy/NetworkProxyPolicyTest.kt'
PATHS = {STORE: DATA + 'core/store/NetworkProxyStore.kt', POLICY: POLICY,
    API: DATA + 'core/network/ApiClient.kt', SECTIONS: SECTIONS}


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def fixed_sources(repo):
    root = Path(repo) / ARCHIVE
    raw = (root / 'manifest.json').read_bytes()
    if sha(raw) != MANIFEST_SHA256:
        raise ValueError('Unreviewed current proxy manifest')
    manifest = json.loads(raw)
    if (manifest['upstreamCommit'] != COMMIT or manifest['hashNormalization'] != 'raw'
            or {r['path'] for r in manifest['files']} != {*PATHS.values(), TEST}
            or len(manifest['files']) != 5):
        raise ValueError('Unknown current proxy source set')
    if {p.name for p in root.iterdir()} != {'manifest.json', *(r['filename'] for r in manifest['files'])}:
        raise ValueError('Unexpected current proxy archive leaf')
    sources = {}
    for row in manifest['files']:
        raw = (root / row['filename']).read_bytes()
        if (row['filename'] != Path(row['path']).name or len(raw) != row['bytes']
                or sha(raw) != row['sha256Bytes']
                or hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest() != row['gitBlob']):
            raise ValueError('Changed current proxy source: ' + row['path'])
        sources[row['path']] = raw
    return sources, manifest


def selected_source(repo, logical_path):
    if logical_path not in PATHS:
        return None
    return fixed_sources(repo)[0][PATHS[logical_path]].decode('utf8')


def emit_tests(repo, output):
    raw = fixed_sources(repo)[0][TEST]
    target = Path(output) / TEST.split('/test/java/', 1)[1]
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)
    return [target]
