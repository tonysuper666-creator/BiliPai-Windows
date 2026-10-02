from pathlib import Path
import difflib
import hashlib
import json
import subprocess
import sys

root = Path(__file__).resolve().parent
main = root.parents[2]
candidate = main.parent / 'BiliPai-v023'
packet = main / 'desktop/.local/stable-original-bangumi-pages-root-parity'
base = '6ac84c8036ed4c75e2944d92d1020566e283e983'

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

def apply(text, hunks):
    original = text
    for hunk in hunks:
        assert sha(hunk['before'].encode()) == hunk['beforeSha256LF']
        assert sha(hunk['after'].encode()) == hunk['afterSha256LF']
        assert hunk['before'] and text.count(hunk['before']) == 1, hunk['name']
        text = text.replace(hunk['before'], hunk['after'], 1)
    inverse = text
    for hunk in reversed(hunks):
        assert inverse.count(hunk['after']) == 1, hunk['name']
        inverse = inverse.replace(hunk['after'], hunk['before'], 1)
    assert inverse == original
    return text

assert not (root / 'installation.json').exists()
assert not git('status', '--porcelain').strip()
head = git('rev-parse', 'HEAD').decode().strip()
assert head == '529a79eb3182f51f449f4bbf5f66ff615113e248'
frozen_raw = read(packet / 'frozen-handoff.json')
assert sha(frozen_raw) == 'c78c0adb9b7453a8aecfba8e01b893eaf9af162c3d83a22b4d62abe19ea99dcb'
frozen = json.loads(frozen_raw)
assert frozen['candidateBase'] == base
assert len(frozen['rawArtifacts']) == frozen['rawArtifactCount'] == 107
for row in frozen['rawArtifacts']:
    path = packet / row['path']
    assert path.resolve().is_relative_to(packet.resolve())
    raw = read(path)
    assert len(raw) == row['sizeBytes'] and sha(raw) == row['sha256Bytes'], row['path']
contract_raw = read(packet / 'install-contract.json')
assert sha(contract_raw) == 'c67d379472dfa3a6860e4c2f76ef9e040b7ddae5b81f96550bac8ef190cb6d70'
contract = json.loads(contract_raw)
assert contract['candidateHead'] == base and not contract['completeOriginalPlayerAccepted']
hunk_raw = read(packet / contract['exactHunks'])
assert sha(hunk_raw) == '7082fcb67b02fae552a01b7cf6e3275207da47b4b6391651ee6b9783cd28f313'
hunks = json.loads(hunk_raw)
targets = json.loads(read(packet / contract['targets']))
assert len(hunks) == 9 and len(targets) == 4
assert len(contract['copyWhitelist']) == 2
changes = []
# Independently replay the entire frozen 6ac delta, then rebase only its disjoint hunks.
for family in targets:
    path = family['path']
    baseline_text = lf(git('show', base + ':' + path)).decode()
    assert sha(baseline_text.encode()) == family['beforeSha256LF'], path
    baseline_after = apply(baseline_text, [h for h in hunks if h['path'] == path])
    assert sha(baseline_after.encode()) == family['afterSha256LF'], path
    if path == 'desktop/upstream-sources.json':
        frozen_registry_after = json.loads(baseline_after)
        continue
    old = read(candidate / path)
    assert lf(old) == lf(git('show', head + ':' + path))
    text = apply(lf(old).decode(), [h for h in hunks if h['path'] == path])
    raw = text.replace('\n', '\r\n').encode() if b'\r\n' in old else text.encode()
    changes.append((path, old, raw))
for row in contract['copyWhitelist']:
    path = row['target']
    assert not wide(candidate / path).exists()
    raw = read(packet / row['source'])
    assert sha(raw) == row['sha256Bytes'], path
    changes.append((path, None, raw))

delta = json.loads(read(packet / contract['registryDelta']))
assert delta['base'] == base and delta['newIdentities'] == 8
def registry_delta(registry):
    additions = unions = 0
    for item in delta['sourceFeaturesUnion']:
        matches = [row for row in registry['sources'] if row['path'] == item['path']]
        if item['operation'] == 'add-original-identity':
            assert not matches, item['path']
            assert sha(lf(read(candidate / item['path']))) == item['row']['sha256']
            registry['sources'].append(dict(item['row']))
            additions += 1
        else:
            assert item['operation'] == 'feature-union'
            assert len(matches) == 1 and matches[0]['sha256'] == item['sha256']
            assert matches[0]['mode'] == item['existingModePreserved']
            assert sha(lf(read(candidate / item['path']))) == item['sha256']
            for feature in item['features']:
                assert feature not in matches[0]['features']
                matches[0]['features'].append(feature)
            unions += 1
    assert (additions, unions) == (8, 4)
    return registry
# The semantic registry operations are exactly equivalent to all five frozen registry
# hunks on their original baseline, while retaining the newer Search unions and identities.
baseline_registry = json.loads(git('show', base + ':desktop/upstream-sources.json'))
registry_delta(baseline_registry)
semantic_rows = {row['path']: row for row in baseline_registry['sources']}
frozen_rows = {row['path']: row for row in frozen_registry_after['sources']}
assert len(semantic_rows) == len(baseline_registry['sources']) == 1191
assert len(frozen_rows) == len(frozen_registry_after['sources']) == 1191
assert semantic_rows == frozen_rows
assert {k:v for k,v in baseline_registry.items() if k != 'sources'} == {k:v for k,v in frozen_registry_after.items() if k != 'sources'}
# Frozen preparation inserts new identities in a different list order. Source identity
# and every field are equal; rebasing keeps the current registry order and appends new rows.
registry_order_difference = list(semantic_rows) != list(frozen_rows)
registry_path = 'desktop/upstream-sources.json'
registry_old = read(candidate / registry_path)
assert lf(registry_old) == lf(git('show', head + ':' + registry_path))
registry = json.loads(registry_old)
assert len(registry['sources']) == 1198 and len(registry['resources']) == 244
before_registry = json.loads(registry_old)
registry_delta(registry)
assert len(registry['sources']) == 1206 and len(registry['resources']) == 244
for old_row in before_registry['sources']:
    new_row = next(row for row in registry['sources'] if row['path'] == old_row['path'])
    assert {k:v for k,v in old_row.items() if k != 'features'} == {k:v for k,v in new_row.items() if k != 'features'}
    assert all(feature in new_row['features'] for feature in old_row['features'])
assert registry['resources'] == before_registry['resources']
registry_new = (json.dumps(registry, indent=2, ensure_ascii=False) + '\n').encode()
if b'\r\n' in registry_old:
    registry_new = registry_new.replace(b'\n', b'\r\n')
changes.append((registry_path, registry_old, registry_new))
assert len(changes) == len({path for path, _, _ in changes}) == 6
receipt = dict(baseCommit=head, preparedBaseCommit=base,
    frozenManifestSha256Bytes=sha(frozen_raw), rawArtifactsVerified=107,
    originalFrozenExactHunks=9, actualRebasedCodeHunks=4, registrySemanticDeltaMatchesFrozenFiveHunks=True,
    registrySemanticComparisonByUniqueIdentity=True, frozenRegistryListOrderDiffers=registry_order_difference,
    newCanonicalFiles=2, newOriginalIdentities=8, sourceFeatureUnions=4,
    searchIdentitiesAndAllExistingFeaturesPreserved=True,
    sourceCount=1206, resourceCount=244, newDependencies=0,
    generatedOrBinaryProductCopies=False, normalCompileAccepted=False,
    rootRuntimeAccepted=False, networkAccepted=False, originalPlayerAccepted=False,
    desktopPackageUpdated=False, sourceTargets=[])
diff = []
for path, old, raw in changes:
    assert old != raw
    diff.extend(difflib.unified_diff(lf(old or b'').decode().splitlines(True),
        lf(raw).decode().splitlines(True), fromfile=path, tofile=path))
    receipt['sourceTargets'].append(dict(path=path,
        beforeSha256Bytes=sha(old) if old is not None else None,
        afterSha256Bytes=sha(raw), afterSha256LF=sha(lf(raw))))
print(json.dumps(dict(preflightPassed=True, productTargets=6, codeHunks=4,
    registryOperations=12, sourceCount=1206, applied='--apply' in sys.argv)))
if '--apply' not in sys.argv:
    sys.exit(0)
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
