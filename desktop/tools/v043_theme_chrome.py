"""Pinned original theme, transparent controls and measured native underlines.

The existing component, native-tab and appearance producers retain ownership.
Their exact selected predecessors are checked before advancing complete sources.
"""
from pathlib import Path
import difflib
import hashlib
import json

COMMIT = '35e2fac8a3870a2227a323e19f29f1e93d7e591c'
ARCHIVE = 'desktop/upstream-slices/v043-theme-chrome'
MANIFEST_SHA256 = 'c36ba0ec38c2c78a9dd49c390d0c36840920e4f7b75d9bc0b325821dbcd47fd5'
DS = 'design-system/src/main/java/com/android/purebilibili/core/ui/'
COMPONENTS = {DS+'ButtonVisualPolicy.kt', DS+'components/AppPrimitiveComponents.kt',
              DS+'renderer/miuix/AppMiuixSwitch.kt'}
NATIVE_RENDERER = DS+'renderer/material3/AppMaterial3SegmentedControl.kt'
SEED_REPLACEMENTS = ('Theme.kt', 'AppThemeEndpointPolicy.kt', 'AppThemeEndpointPolicyTest.kt')
NEW_SEED_TESTS = ('CustomSelectionContentColorTest.kt', 'MiuixThemeBridgePolicyTest.kt')
COMPONENT_TESTS = ('ButtonVisualPolicyTest.kt', 'ButtonSlotColorTest.kt', 'AppTabIndicatorPolicyTest.kt')
EXPECTED = {'ButtonVisualPolicy.kt', 'AppPrimitiveComponents.kt', 'AppMaterial3SegmentedControl.kt',
            'AppMiuixSwitch.kt', 'AppThemeEndpointPolicy.kt', 'Theme.kt', 'ButtonVisualPolicyTest.kt',
            'ButtonSlotColorTest.kt', 'AppTabIndicatorPolicyTest.kt', 'AppThemeEndpointPolicyTest.kt',
            'CustomSelectionContentColorTest.kt', 'MiuixThemeBridgePolicyTest.kt'}


def sha(raw):
    return hashlib.sha256(raw.encode('utf8') if isinstance(raw, str) else raw).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def fixed_sources(repo):
    root = Path(repo)/ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing or linked theme chrome originals')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json'}, 'Unexpected theme chrome leaves')
    raw = (root/'manifest.json').read_bytes()
    need(sha(raw) == MANIFEST_SHA256, 'Theme chrome manifest pin changed')
    manifest = json.loads(raw)
    need((manifest['schemaVersion'], manifest['repository'], manifest['tag'], manifest['commit'],
          manifest['hashNormalization']) == (1, 'jay3-yy/BiliPai', 'v0.4.3', COMMIT, 'raw'),
         'Theme chrome source identity changed')
    need({r['filename'] for r in manifest['files']} == EXPECTED and len(manifest['files']) == len(EXPECTED),
         'Theme chrome source set changed')
    result = {}
    for row in manifest['files']:
        p = root/row['filename']
        need(p.is_file() and not p.is_symlink() and Path(row['path']).name == row['filename'],
             'Linked or ambiguous theme chrome original')
        raw = p.read_bytes()
        need(len(raw) == row['bytes'] and sha(raw) == row['sha256'], 'Theme chrome bytes changed: '+p.name)
        oid = hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
        need(oid == row['gitBlob'], 'Theme chrome Git blob changed: '+p.name)
        result[p.name] = raw
    return result, manifest


def validate_outputs(repo, *outputs):
    archive = (Path(repo)/ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(archive) or archive.is_relative_to(target)),
             'Output overlaps fixed theme chrome originals')
    fixed_sources(repo)


def advance(repo, name, before):
    originals, manifest = fixed_sources(repo)
    old = before.encode('utf8') if isinstance(before, str) else before
    selected = originals[name]
    reference = manifest['reviewedPredecessors'][name]
    need(sha(old) == reference['predecessorSHA256'], 'Theme chrome selected predecessor changed: '+name)
    a, b = old.splitlines(keepends=True), selected.splitlines(keepends=True)
    ops = [op for op in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes() if op[0] != 'equal']
    actual = [[tag,i,j,k,l,sha(b''.join(a[i:j])),sha(b''.join(b[k:l]))] for tag,i,j,k,l in ops]
    need(actual == reference['hunks'], 'Unreviewed theme chrome hunks: '+name)
    forward, inverse = a[:], b[:]
    for _,i,j,k,l in reversed(ops):
        forward[i:j] = b[k:l]
        inverse[k:l] = a[i:j]
    need(b''.join(forward) == selected and b''.join(inverse) == old,
         'Theme chrome whole predecessor inverse failed: '+name)
    return selected


def component_source(repo, path, previous):
    return advance(repo, Path(path).name, previous).decode('utf8') if path in COMPONENTS else previous


def native_renderer_source(repo, path, previous):
    return advance(repo, 'AppMaterial3SegmentedControl.kt', previous).decode('utf8') if path == NATIVE_RENDERER else previous


def update_component_tests(repo, tests):
    if tests is None:
        return
    validate_outputs(repo, tests)
    sources, manifest = fixed_sources(repo)
    for name in COMPONENT_TESTS:
        row = next(row for row in manifest['files'] if row['filename'] == name)
        target = Path(tests)/row['path'].split('/src/test/java/',1)[1]
        if name in manifest['reviewedPredecessors']:
            need(target.is_file(), 'Missing existing theme component test owner: '+name)
            need(advance(repo, name, target.read_bytes()) == sources[name], 'Theme component test advance failed')
        elif target.exists():
            need(target.is_file() and target.read_bytes() == sources[name], 'Foreign theme component test output')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(sources[name])


def seed_sources(repo, previous):
    originals, manifest = fixed_sources(repo)
    result = dict(previous)
    for name in SEED_REPLACEMENTS:
        result[name] = advance(repo, name, previous[name])
    for name in NEW_SEED_TESTS:
        need(name not in previous, 'Additional original theme test already has an owner: '+name)
        result[name] = originals[name]
    return result


def seed_source_identity(repo, name, base_commit, base_manifest):
    if name not in SEED_REPLACEMENTS + NEW_SEED_TESTS:
        return dict(upstreamCommit=base_commit, sourceManifestSHA256=base_manifest)
    _, manifest = fixed_sources(repo)
    previous = manifest['reviewedPredecessors'].get(name)
    return dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
                predecessorSHA256=previous['predecessorSHA256'] if previous else None,
                completePredecessorInverse=previous is not None)


def inventory(repo):
    _, manifest = fixed_sources(repo)
    return [dict(path=row['path'], upstreamCommit=COMMIT, sha256=row['sha256'],
        archive=ARCHIVE+'/'+row['filename'], mode='whole-original-test' if row['cases'] is not None else
        'selected-original-declarations' if row['filename']=='Theme.kt' else 'whole-original',
        features=['v043-theme-chrome-fidelity']) for row in manifest['files']]
