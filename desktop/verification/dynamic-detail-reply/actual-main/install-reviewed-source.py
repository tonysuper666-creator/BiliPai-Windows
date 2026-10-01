"""Install the reviewed source-only handoff, preserving the existing Ops body."""
from pathlib import Path
import hashlib
import json
import subprocess

ROOT = Path(__file__).resolve().parents[3]
LANE = ROOT / 'desktop/.local/dynamic-detail-reply-parity'
OUT = Path(__file__).resolve().parent


def safe(path):
    value = str(path.absolute())
    prefix = chr(92) * 2 + '?' + chr(92)
    return Path(value if value.startswith(prefix) else prefix + value)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def checked(row):
    data = safe(LANE / row['path']).read_bytes()
    assert len(data) == row['bytes'], row['path']
    assert digest(data) == row['sha256Bytes'], row['path']
    return data


manifest_path = LANE / 'raw-handoff-final/frozen-handoff.json'
manifest_bytes = manifest_path.read_bytes()
assert digest(manifest_bytes) == '4a7950229a7bf2eed938f9c9979b395696714634e2b690003adb062c66460852'
manifest = json.loads(manifest_bytes)
assert manifest['artifactCount'] == len(manifest['artifacts']) == 445
for row in manifest['artifacts']:
    checked(row)

payloads = json.loads((LANE / 'raw-handoff-final/installation-payloads.json').read_bytes())['payloads']
new_payloads = [row for row in payloads if row['operation'] == 'newFile']
assert len(new_payloads) == 8
for row in new_payloads:
    source = checked(row['source'])
    target = ROOT / row['target']
    assert not target.exists() or target.read_bytes() == source, row['target']

ops = next(row for row in payloads if row['operation'] == 'appendMemberFragmentsOnly')
ops_path = ROOT / ops['target']
base = subprocess.check_output(['git', 'show', 'HEAD:' + ops['target']], cwd=ROOT)
assert digest(base) == ops['baseSha256Bytes']
desired = checked(ops['compiledDesired'])
checked(ops['patch'])
base_lf = base.decode('utf-8').replace('\r\n', '\n')
desired_lf = desired.decode('utf-8').replace('\r\n', '\n')
assert base_lf.endswith('\n}\n')
prefix = base_lf[:-2]
assert desired_lf.startswith(prefix), 'Existing Operations members must be unchanged.'
comment_marker = '// Paste inside existing DesktopDynamicCardOperations; no package/class/API/model producer.'
detail_marker = '// Additional members inside the existing DesktopDynamicCardOperations; no replacement Ops file.'
assert desired_lf.count(comment_marker) == desired_lf.count(detail_marker) == 1
assert desired_lf.index(comment_marker) < desired_lf.index(detail_marker)
assert desired_lf.count('suspend fun getPublishedDynamicDetail') == 1
assert 'read { dynamic.getDynamicDetail(id) }' in desired_lf
assert ops_path.read_text(encoding='utf-8').replace('\r\n', '\n') in (base_lf, desired_lf)

inventory = json.loads((LANE / 'raw-handoff-final/original-source-inventory.json').read_bytes())
registry_path = ROOT / 'desktop/upstream-sources.json'
registry = json.loads(registry_path.read_bytes())
assert registry['upstreamTag'] == inventory['originalTag']
assert registry['upstreamCommit'] == inventory['originalCommit']
by_path = {row['path']: row for row in registry['sources']}
assert len(by_path) == len(registry['sources']) == 588
added = 0
for row in inventory['sources']:
    data = safe(ROOT / row['path']).read_bytes().replace(b'\r\n', b'\n')
    assert digest(data) == row['sha256'], row['path']
    existing = by_path.get(row['path'])
    assert (existing is not None) == row['existingIdentity']
    if existing:
        assert existing['sha256'] == row['sha256']
        for feature in row['mergeFeatures']:
            if feature not in existing['features']:
                existing['features'].append(feature)
    else:
        assert row['mode'] != 'direct', 'New policy and UI identities have selected producers.'
        existing = {key: row[key] for key in ('path', 'sha256', 'mode')}
        existing['features'] = list(row['mergeFeatures'])
        registry['sources'].append(existing)
        by_path[row['path']] = existing
        added += 1
assert added == 14 and len(registry['sources']) == 602

for row in new_payloads:
    target = ROOT / row['target']
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(checked(row['source']))
# Append the checked suffix rather than installing a competing Ops producer.
ops_path.write_bytes((prefix + desired_lf[len(prefix):]).encode('utf-8'))
assert digest(ops_path.read_bytes()) == ops['desiredSha256Bytes']
registry_path.write_text(json.dumps(registry, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
OUT.mkdir(parents=True, exist_ok=True)
(OUT / 'installation-receipt.json').write_text(json.dumps({
    'handoffSha256Bytes': digest(manifest_bytes), 'verifiedArtifacts': 445,
    'installedNewSources': [row['target'] for row in new_payloads],
    'existingOperationsPreserved': True, 'opsSha256Bytes': digest(ops_path.read_bytes()),
    'originalIdentitiesMerged': 13, 'originalIdentitiesAdded': 14, 'registryCount': 602,
    'compiledClassesInstalled': False, 'mainConsumerIntegrated': False,
}, indent=2) + '\n', encoding='utf-8')
print('PASS: eight source payloads, append-only Ops, 27 original identities; no binaries installed.')
