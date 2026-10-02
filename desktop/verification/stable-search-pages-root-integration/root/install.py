from pathlib import Path
import difflib
import hashlib
import json
import subprocess
import sys

root = Path(__file__).resolve().parent
main = root.parents[2]
candidate = main.parent / 'BiliPai-v023'
packet = main / 'desktop/.local/stable-original-search-pages-root-parity'

def wide(path):
    value = str(path.absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)

def read(path):
    return wide(path).read_bytes()

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

def lf(raw):
    return raw.replace(b'\r\n', b'\n')

def git(*args):
    return subprocess.check_output(['git', '-c', 'core.longpaths=true', *args], cwd=candidate)

assert not (root / 'installation.json').exists()
assert not git('status', '--porcelain').strip()
head = git('rev-parse', 'HEAD').decode().strip()
assert head == '6ac84c8036ed4c75e2944d92d1020566e283e983'
frozen_raw = read(packet / 'frozen-handoff.json')
assert sha(frozen_raw) == '86589bf1f6e24b93d12c94143ca68119cf1e0c603c5e6f7bbc76767b0d6d05ec'
frozen = json.loads(frozen_raw)
assert frozen['frozen'] and frozen['baseline'] == head
assert len(frozen['rawFiles']) == frozen['rawCount'] == 73
for row in frozen['rawFiles']:
    path = packet / row['path']
    assert path.resolve().is_relative_to(packet.resolve())
    assert Path(row['absolutePath']).resolve() == path.resolve()
    raw = read(path)
    assert len(raw) == row['bytes'] and sha(raw) == row['sha256Bytes'], row['path']
contract_raw = read(packet / 'install-contract.json')
assert sha(contract_raw) == frozen['installContract']['sha256Bytes']
contract = json.loads(contract_raw)
assert contract['baseline'] == head and not contract['candidateWritten']
assert len(contract['existingFamilies']) == 10 and len(contract['newCanonicalFiles']) == 3
changes = []
total_hunks = 0
for family in contract['existingFamilies']:
    path = family['path']
    old = read(candidate / path)
    assert sha(git('show', head + ':' + path)) == family['baselineGitBytesSha'], path
    original = lf(old).decode('utf-8')
    assert sha(original.encode()) == family['baselineLFsha'], path
    prepared = read(Path(family['preparedPath']))
    assert Path(family['preparedPath']).resolve().is_relative_to(packet.resolve())
    assert sha(prepared) == family['preparedBytesSha'], path
    text = original
    for hunk in family['exactHunks']:
        assert sha(hunk['before'].encode()) == hunk['beforeSha256LF']
        assert sha(hunk['after'].encode()) == hunk['afterSha256LF']
        assert hunk['before'] and text.count(hunk['before']) == 1, path
        text = text.replace(hunk['before'], hunk['after'], 1)
    assert sha(text.encode()) == family['prospectiveLFsha'], path
    assert text.encode() == lf(prepared), path
    inverse = text
    for hunk in reversed(family['exactHunks']):
        assert inverse.count(hunk['after']) == 1, path
        inverse = inverse.replace(hunk['after'], hunk['before'], 1)
    assert inverse == original, path
    total_hunks += len(family['exactHunks'])
    raw = text.replace('\n', '\r\n').encode() if b'\r\n' in old else text.encode()
    changes.append((path, old, raw))
assert total_hunks == 15
for row in contract['newCanonicalFiles']:
    path = row['path']
    assert not wide(candidate / path).exists()
    assert Path(row['preparedPath']).resolve().is_relative_to(packet.resolve())
    raw = read(Path(row['preparedPath']))
    assert sha(raw) == row['sha256Bytes'], path
    changes.append((path, None, raw))

delta_raw = read(Path(contract['registryDeltaFile']))
assert sha(delta_raw) == frozen['registryDelta']['sha256Bytes']
delta = json.loads(delta_raw)
assert delta['baseline'] == head
registry_path = 'desktop/upstream-sources.json'
registry_old = read(candidate / registry_path)
assert lf(registry_old) == lf(git('show', head + ':' + registry_path))
registry = json.loads(registry_old)
assert len(registry['sources']) == delta['baselineSources'] == 1183
assert len(registry['resources']) == delta['baselineResources'] == 244
assert len(delta['newSources']) == 15
assert len(delta['existingSourceUnions']) == 15
assert len(delta['existingResourceUnions']) == 3
for row in delta['newSources']:
    assert not any(item['path'] == row['path'] for item in registry['sources']), row['path']
    assert sha(lf(read(candidate / row['path']))) == row['sha256'], row['path']
    registry['sources'].append(row)
for group, rows in (('sources', delta['existingSourceUnions']), ('resources', delta['existingResourceUnions'])):
    for row in rows:
        matches = [item for item in registry[group] if item['path'] == row['path']]
        assert len(matches) == 1 and matches[0]['sha256'] == row['sha256'], row['path']
        assert sha(lf(read(candidate / row['path']))) == row['sha256'], row['path']
        if 'baselineMode' in row:
            assert matches[0]['mode'] == row['baselineMode'], row['path']
        assert row['preserveEveryExistingField']
        for feature in row['appendFeatures']:
            assert feature not in matches[0]['features']
            matches[0]['features'].append(feature)
assert len(registry['sources']) == 1198 and len(registry['resources']) == 244
registry_new = (json.dumps(registry, indent=2, ensure_ascii=False) + '\n').encode()
if b'\r\n' in registry_old:
    registry_new = registry_new.replace(b'\n', b'\r\n')
changes.append((registry_path, registry_old, registry_new))
gradle_path = 'desktop/build.gradle.kts'
gradle_old = read(candidate / gradle_path)
assert lf(gradle_old) == lf(git('show', head + ':' + gradle_path))
append_raw = read(Path(contract['gradleAppendFile']))
assert sha(append_raw) == frozen['gradleAppend']['sha256Bytes']
assert b'val extractOriginalSearchPages by' not in gradle_old
if b'\r\n' in gradle_old:
    append_raw = lf(append_raw).replace(b'\n', b'\r\n')
changes.append((gradle_path, gradle_old, gradle_old + append_raw))
assert len(changes) == len({path for path, _, _ in changes}) == 15
receipt = dict(baseCommit=head, frozenManifestSha256Bytes=sha(frozen_raw), rawArtifactsVerified=73,
    existingFamilies=10, exactHunks=15, newCanonicalFiles=3, newOriginalIdentities=15,
    sourceFeatureUnions=15, resourceFeatureUnions=3, sourceCount=1198, resourceCount=244,
    newDependencies=0, wholePreparedExistingCopies=False, generatedOrBinaryProductCopies=False,
    normalCompileAccepted=False, rootRuntimeAccepted=False, networkAccepted=False,
    desktopPackageUpdated=False, sourceTargets=[])
diff = []
for path, old, raw in changes:
    assert old != raw
    diff.extend(difflib.unified_diff(lf(old or b'').decode().splitlines(True),
        lf(raw).decode().splitlines(True), fromfile=path, tofile=path))
    receipt['sourceTargets'].append(dict(path=path,
        beforeSha256Bytes=sha(old) if old is not None else None,
        afterSha256Bytes=sha(raw), afterSha256LF=sha(lf(raw))))
print(json.dumps(dict(preflightPassed=True, productTargets=15, exactHunks=15,
    sourceCount=1198, resourceCount=244, applied='--apply' in sys.argv)))
if '--apply' not in sys.argv:
    sys.exit(0)
# Every pinned input, original registry identity and exact inverse is checked before writes.
for path, old, raw in changes:
    destination = candidate / path
    assert destination.resolve().is_relative_to(candidate.resolve())
    assert (read(destination) if wide(destination).exists() else None) == old
    if old is not None:
        backup = root / 'before' / path
        wide(backup.parent).mkdir(parents=True, exist_ok=True)
        wide(backup).write_bytes(old)
    wide(destination.parent).mkdir(parents=True, exist_ok=True)
    wide(destination).write_bytes(raw)
    assert read(destination) == raw
(root / 'source-diff.patch').write_text(''.join(diff), encoding='utf-8', newline='\n')
(root / 'installation.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
