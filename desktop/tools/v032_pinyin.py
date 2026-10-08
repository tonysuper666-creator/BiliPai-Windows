"""Complete fixed v032 PinyinUtils, emitted by the existing settings-search owner.

The v025 canonical catalog and source bodies stay unchanged. This slice owns one
replacement FQCN and makes no cache, UI, account, or navigation owner.
"""
from pathlib import Path
import hashlib
import json
import os
from v025_source_paths import canonical_source

COMMIT = '1db8665cb9706dca44fcae0f540f2a3440721089'
ORIGIN = 'app/src/main/java/com/android/purebilibili/core/util/PinyinUtils.kt'
ARCHIVE = Path('desktop/upstream-slices/v032-pinyin')
MANIFEST_SHA256 = '729fbf409519ab92a8cd089de45582d669e838db1dd94569e3b63ce780a06dde'
RAW_SHA256 = '6fcd4051fd110e5cba6fa8e04bcadc4f45f190b89193b82620a44cf5b33a5ff9'
GIT_BLOB = '422016721430eb4e9f2b91805a424f79e48e9be2'

def wide(path):
    value = os.path.abspath(path)
    return Path('\\\\?\\' + value if os.name == 'nt' and not value.startswith('\\\\?\\') else value)

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

def emit(repo, output):
    archive = Path(repo) / ARCHIVE
    manifest_raw = wide(archive / 'manifest.json').read_bytes()
    if sha(manifest_raw) != MANIFEST_SHA256:
        raise ValueError('Fixed v032 pinyin manifest changed')
    manifest = json.loads(manifest_raw)
    if manifest['upstreamCommit'] != COMMIT or manifest['upstreamTag'] != 'v0.3.2' or len(manifest['sources']) != 1:
        raise ValueError('Unknown fixed v032 pinyin identity')
    row = manifest['sources'][0]
    if row['path'] != 'PinyinUtils.kt' or row['origin'] != ORIGIN:
        raise ValueError('Unknown pinyin source boundary')
    raw = wide(archive / 'PinyinUtils.kt').read_bytes()
    git_blob = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
    if type(row['bytes']) is not int or len(raw) != row['bytes'] or sha(raw) != RAW_SHA256 or row['sha256'] != RAW_SHA256 or git_blob != GIT_BLOB or row['gitBlob'] != GIT_BLOB:
        raise ValueError('Fixed complete PinyinUtils bytes changed')
    canonical = canonical_source(repo, ORIGIN).read_bytes().replace(b'\r\n', b'\n')
    destination = wide(Path(output) / ORIGIN.split('/src/main/java/', 1)[1])
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(raw)
    proof = dict(upstreamTag='v0.3.2', upstreamCommit=COMMIT,
                 canonicalSourceSha256LF=sha(canonical), manifestSha256=MANIFEST_SHA256,
                 sourceBodiesVerbatim=True, fullSourceRows=manifest['sources'],
                 canonicalSourcesRewritten=False, fullV032ParityClaimed=False,
                 owner='extract-upstream-settings-search.py',
                 newRuntimeCacheOrUiOwner=False,
                 contextualPolyphoneDisambiguationClaimed=False)
    wide(Path(output) / 'v032-pinyin-source-ownership.json').write_text(
        json.dumps(proof, ensure_ascii=True, indent=2) + '\n',
        encoding='utf8', newline='\n')
    return destination
