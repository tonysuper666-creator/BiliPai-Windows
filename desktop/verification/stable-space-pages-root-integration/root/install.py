from pathlib import Path
import copy
import difflib
import hashlib
import json
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8')
root = Path(__file__).resolve().parent
main = root.parents[2]
candidate = main.parent / 'BiliPai-v023'
packet = main / 'desktop/.local/stable-original-space-pages-root-parity'

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
assert not git('status', '--porcelain', '-z').strip()
frozen_raw = read(packet / 'frozen-handoff.json')
assert sha(frozen_raw) == 'b2b21eb2e4a6640db9b00ad13ec52ac1df313dbfa7f7d90385dd64ed5514f017'
frozen = json.loads(frozen_raw)
assert len(frozen['files']) == len({r['path'] for r in frozen['files']}) == 791
for row in frozen['files']:
    path = Path(row['path'])
    assert not path.is_absolute() and '..' not in path.parts
    raw = read(packet / path)
    assert len(raw) == row['bytes'] and sha(raw) == row['sha256Bytes'], row['path']
contract_raw = read(packet / 'install-contract.json')
assert sha(contract_raw) == '138fc883ca553779bc3ba70d884ec4f85483397e7b5aa948788e85e2f7a3a6de'
contract = json.loads(contract_raw)
assert git('rev-parse', 'HEAD').decode().strip() == contract['candidateBase']
assert contract['sourceCountBefore'] == 1225 and contract['sourceCountAfter'] == 1229
assert not contract['rootRuntimeAccepted'] and not contract['installedExeAccepted']

def metadata(key):
    raw = read(packet / contract[key])
    assert sha(raw) == contract[key + 'Sha256']
    return json.loads(raw)

replay = metadata('originalReplay')
assert replay['pin'] == contract['upstreamCommit']
inverse_rows = []
for row in replay['files']:
    assert row['completeOriginalFileReconstructed']
    generated = read(packet / 'generated' / row['generated']).decode().replace('\r\n', '\n')
    assert sha(generated.encode()) == row['generatedSha256LF']
    original = generated
    for step in reversed(row['transforms']):
        assert sha(original.encode()) == step['afterSha256']
        for pos in reversed(step['positionsAfter']):
            assert original[pos:pos + len(step['adapted'])] == step['adapted']
            original = original[:pos] + step['original'] + original[pos + len(step['adapted']):]
        assert sha(original.encode()) == step['beforeSha256']
    pinned = git('show', contract['upstreamCommit'] + ':' + row['source']).decode().replace('\r\n', '\n')
    assert pinned == original == lf(read(candidate / row['source'])).decode()
    assert sha(original.encode()) == row['sha256LF']
    inverse_rows.append(dict(source=row['source'], generated=row['generated'], originalByteEqualLF=True,
        transforms=len(row['transforms'])))
assert len(inverse_rows) == 11

exact = metadata('exactHunks')
assert exact['candidateHead'] == contract['candidateBase']
assert exact['targets'] == contract['existingCodeTargets']
before, desired, exact_rows = {}, {}, []
for row in contract['existingCodeTargets']:
    name = row['target']
    raw = read(candidate / name)
    original = lf(raw).decode()
    assert sha(raw) == row['baseRawSha256'] and sha(original.encode()) == row['baseLfSha256']
    text = original
    inverse = []
    for hunk in row['hunks']:
        assert text.count(hunk['before']) == 1
        pos = text.index(hunk['before'])
        text = text[:pos] + hunk['after'] + text[pos + len(hunk['before']):]
        assert pos == hunk['offsetAfter']
        inverse.append((pos, hunk))
    assert sha(text.encode()) == row['desiredLfSha256']
    reversed_text = text
    for pos, hunk in reversed(inverse):
        assert reversed_text[pos:pos + len(hunk['after'])] == hunk['after']
        reversed_text = reversed_text[:pos] + hunk['before'] + reversed_text[pos + len(hunk['after']):]
    assert reversed_text == original
    output = text.encode() if b'\r\n' not in raw else text.replace('\n', '\r\n').encode()
    assert sha(output) == row['desiredRawSha256']
    before[name], desired[name] = raw, output
    exact_rows.append(dict(path=name, exactHunks=len(row['hunks']), originalReconstructed=True))
assert sum(row['exactHunks'] for row in exact_rows) == 14
for row in contract['copyNewTargets']:
    name = row['target']
    assert name not in desired and not wide(candidate / name).exists()
    raw = read(packet / row['prepared'])
    assert sha(raw) == row['sha256Bytes']
    before[name], desired[name] = None, raw

name = 'desktop/build.gradle.kts'
raw = read(candidate / name)
fragment = read(packet / contract['gradleFragment'])
assert sha(raw) == contract['gradleBaseRawSha256']
assert sha(fragment) == contract['gradleFragmentSha256']
assert b'val extractOriginalSpacePages' not in raw
before[name], desired[name] = raw, raw + fragment

inventory = metadata('sourceInventory')
name = 'desktop/upstream-sources.json'
raw = read(candidate / name)
assert sha(raw) == contract['registryBaseRawSha256']
registry = json.loads(raw)
updated = copy.deepcopy(registry)
by_path = {r['path']: r for r in updated['sources']}
assert len(by_path) == len(registry['sources']) == 1225
unions = additions = 0
for row in inventory['sources']:
    path = row['path']
    original = git('show', contract['upstreamCommit'] + ':' + path)
    assert sha(lf(original)) == row['sha256'] and lf(read(candidate / path)) == lf(original)
    if row['operation'] == 'feature-union':
        existing = by_path[path]
        assert existing['features'] == row['priorFeatures']
        assert existing['sha256'] == row['sha256'] and existing['mode'] == row['mode']
        existing['features'] = list(dict.fromkeys(existing['features'] + row['features']))
        unions += 1
    else:
        assert row['operation'] == 'append' and path not in by_path
        item = {k: row[k] for k in ('path', 'sha256', 'mode', 'features')}
        updated['sources'].append(item)
        by_path[path] = item
        additions += 1
assert (unions, additions) == (8, 4)
assert len(updated['sources']) == len(by_path) == 1229
assert registry['resources'] == updated['resources'] and len(updated['resources']) == 244
output = (json.dumps(updated, ensure_ascii=False, indent=2) + '\n').encode()
before[name], desired[name] = raw, output
assert len(desired) == 11

# Hashes, source inversion, every exact hunk and all append/union boundaries
# have passed before the first production write. Existing overlays are never copied.
for name, raw in before.items():
    if raw is not None:
        backup = wide(root / 'before' / name)
        backup.parent.mkdir(parents=True, exist_ok=True)
        backup.write_bytes(raw)
for name, raw in desired.items():
    target = wide(candidate / name)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)
targets, patch = [], []
for name, raw in desired.items():
    assert read(candidate / name) == raw
    targets.append(dict(path=name, beforeSha256Bytes=sha(before[name]) if before[name] is not None else None,
        afterSha256Bytes=sha(raw), afterSha256LF=sha(lf(raw))))
    patch.extend(difflib.unified_diff(lf(before[name] or b'').decode().splitlines(keepends=True),
        lf(raw).decode().splitlines(keepends=True), fromfile='a/' + name, tofile='b/' + name))
receipt = dict(baseCommit=contract['candidateBase'], sourceTargets=targets, sourceCount=1229, resourceCount=244,
    exactExistingCodeTargets=4, exactExistingFragments=14, newCanonicalFiles=5,
    gradleSingleProducerAppend=True, registryAppends=4, registryFeatureUnions=8,
    actualProductOverrides=0, independentlyValidatedFrozenArtifacts=791,
    frozenManifestSha256Bytes=sha(frozen_raw), frozenContractSha256Bytes=sha(contract_raw),
    sourceInverseReplays=inverse_rows, existingFileInverseReplays=exact_rows,
    newDependencies=[], rootRuntimeAccepted=False, realAccountAccepted=False,
    desktopPackageUpdated=False, allFeaturesComplete=False)
(root / 'installation.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf8')
(root / 'source-diff.patch').write_text(''.join(patch), encoding='utf8', newline='\n')
print(json.dumps({k: v for k, v in receipt.items() if k not in ('sourceTargets', 'sourceInverseReplays', 'existingFileInverseReplays')}, indent=2))
