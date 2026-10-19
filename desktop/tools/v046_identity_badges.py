"""v0.4.6 UserVipBadge.kt, including resolveUserVipPink and userVipPink, on the home-page output."""
from pathlib import Path
import hashlib
import json

from v044_favorite_detail import inverse_proof

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-identity-badges'
MANIFEST_SHA256 = 'c766fa05595473479850ae800d36fbd9bc11601528def40957688f9d6119cd75'
EXPECTED = {'UserVipBadge.kt'}
OUTPUT_RELATIVE = 'com/android/purebilibili/core/ui/components/UserVipBadge.kt'
HEADER = ('// Original source design-system/src/main/java/com/android/purebilibili/core/ui/components/UserVipBadge.kt\n'
          '// LF SHA256 79f11b9ec6ad54a8b3f131fd0213b43ed59faf888f451936b688b53f9e8db248\n')


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def fixed(repo):
    root = Path(repo)/ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing original identity badge inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'Identity badge input leaves changed')
    raw = (root/'manifest.json').read_bytes()
    need(sha(raw) == MANIFEST_SHA256, 'Identity badge manifest changed')
    manifest = json.loads(raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Identity badge source identity changed')
    need(len(manifest['files']) == 1 and {row['filename'] for row in manifest['files']} == EXPECTED,
         'Identity badge source set changed')
    sources = {}
    for row in manifest['files']:
        file = root/row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous identity badge source')
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'Identity badge bytes changed: '+file.name)
        need(hashlib.sha1(b'blob '+str(len(leaf)).encode()+b'\0'+leaf).hexdigest() == row['gitBlob'],
             'Identity badge Git blob changed: '+file.name)
        sources[file.name] = leaf.decode('utf8')
    license_file = root/'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'],
         'Identity badge license changed')
    pinned = manifest['beforeFinalOutputs'][OUTPUT_RELATIVE]['sha256']
    need(len(pinned) == 64 and pinned == '1b5aa17cde1232f1405fafd6d4464d46d9869f4d61ab1e2f3e9420a60cb6fce5',
         'Identity badge predecessor pin changed')
    return sources, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo)/ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps original identity badge inputs')


def restore(value, proof):
    need(sha(value) == proof['afterSHA256'], 'Identity badge final output differs from its receipt')
    lines = value.splitlines(keepends=True)
    for hunk in reversed(proof['hunks']):
        first, last = hunk['afterLines']
        need(''.join(lines[first:last]) == hunk['after'], 'Identity badge inverse span changed')
        lines[first:last] = hunk['before'].splitlines(keepends=True)
    result = ''.join(lines)
    need(sha(result) == proof['beforeSHA256'], 'Identity badge predecessor inverse failed')
    return result


def advance(repo, before):
    sources, manifest = fixed(repo)
    need(sha(before) == manifest['beforeFinalOutputs'][OUTPUT_RELATIVE]['sha256'],
         'UserVipBadge predecessor changed')
    need(before.startswith(HEADER), 'UserVipBadge producer header changed')
    original = sources['UserVipBadge.kt']
    need(original.startswith('package com.android.purebilibili.core.ui.components\n') and original.endswith('\n'),
         'UserVipBadge original file shape changed')
    need('fun resolveUserVipPink(' in original and 'fun userVipPink(' in original, 'UserVipPink definer missing')
    # The two header lines are the home-page producer's bookkeeping for the pinned tree.
    # The Kotlin body is the whole v0.4.6 file, which drops AppSurfaceTokens and remember.
    current = HEADER+original
    proof = dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, completeOriginalFile=True,
                 producerHeaderRetained=True,
                 originalToWindows=inverse_proof(original, current),
                 retainedToCurrent=inverse_proof(before, current))
    need(restore(current, proof['originalToWindows']) == original, 'UserVipBadge original inverse failed')
    need(restore(current, proof['retainedToCurrent']) == before, 'UserVipBadge retained inverse failed')
    return current, proof
