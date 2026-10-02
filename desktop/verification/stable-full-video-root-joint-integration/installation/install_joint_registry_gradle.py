from pathlib import Path
import copy, hashlib, json, subprocess, sys
sys.stdout.reconfigure(encoding='utf-8')
HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = MAIN / 'desktop/.local/stable-root-registry-gradle-preflight'
sha = lambda value: hashlib.sha256(value).hexdigest()
plan_bytes = (LANE / 'plan.json').read_bytes()
assert sha(plan_bytes) == 'd59427bafc66f36837985c506c09756247ad5b47cbbf1109987b5043cc7ff5a2'
assert sha((LANE / 'frozen-plan.json').read_bytes()) == '0fbd4752a64b038453b4533fb5110f687e18d44e2516660530449ecd76e0ae52'
plan = json.loads(plan_bytes)
assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip() == plan['candidateHead']
source_receipt = json.loads((HERE / 'joint-source-installation-01.json').read_bytes())
assert source_receipt['candidateBaseHead'] == plan['candidateHead']
for row in source_receipt['files']:
    assert sha((REPO / row['path']).read_bytes()) == row['afterSHA256Bytes'], row['path']
registry_path = REPO / 'desktop/upstream-sources.json'
gradle_path = REPO / 'desktop/build.gradle.kts'
registry_before, gradle_before = registry_path.read_bytes(), gradle_path.read_bytes()
assert sha(registry_before) == plan['candidateRegistrySHA256Bytes']
assert sha(gradle_before) == plan['candidateGradleSHA256Bytes']
manifest = json.loads(registry_before)
original = copy.deepcopy(manifest)
rows = {row['path']: row for row in manifest['sources']}
assert len(rows) == len(manifest['sources']) == plan['originalSourceCount'] == 1170
assert len(manifest['resources']) == plan['resourceCountUnchanged'] == 213
for request in plan['existingFeatureUnions']:
    row = rows[request['path']]
    assert row['sha256'] == request['sha256LF'] and row['mode'] == request['existingMode']
    assert sha(json.dumps(row['features'], ensure_ascii=False, separators=(',', ':')).encode()) == request['currentFeaturesSHA256']
    assert sha((REPO / row['path']).read_bytes().replace(b'\r\n', b'\n')) == row['sha256']
    for feature in request['addFeatures']:
        if feature not in row['features']: row['features'].append(feature)
for request in plan['newIdentities']:
    row = copy.deepcopy(request['row'])
    assert row['path'] not in rows and row['mode'] == 'policy-extract'
    assert sha((REPO / row['path']).read_bytes().replace(b'\r\n', b'\n')) == row['sha256']
    rows[row['path']] = row
    manifest['sources'].append(row)
assert len(manifest['sources']) == plan['expectedOriginalSourceCountAfterExactUnionsAndAdds'] == 1172
assert manifest['resources'] == original['resources']
for key in original:
    if key != 'sources': assert manifest[key] == original[key], key
union_paths = {row['path'] for row in plan['existingFeatureUnions']}
for old in original['sources']:
    current = rows[old['path']]
    if old['path'] not in union_paths: assert current == old
    else:
        assert {k: v for k, v in current.items() if k != 'features'} == {k: v for k, v in old.items() if k != 'features'}
        assert current['features'][:len(old['features'])] == old['features']
append = (LANE / plan['gradleAppendFile']).read_bytes()
assert sha(append) == plan['gradleAppendSHA256Bytes'] == '33aa558573daebc1a1f4cd3b22682b4896899bedb3d16c27b9ef8feeacce095e'
gradle_text = gradle_before.decode('utf-8')
anchor = plan['gradleAppendAnchor']
assert gradle_text.count(anchor) == plan['gradleAppendAnchorCount'] == 1 and gradle_text.endswith(anchor)
for task in plan['gradleAppends']:
    declaration = 'val ' + task['taskName'] + ' by tasks.registering'
    assert declaration not in gradle_text and append.decode('utf-8').count(declaration) == 1
    assert (REPO / task['destination']).is_file()
registry_after = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode()
gradle_after = gradle_before + append
receipt_path = HERE / 'joint-registry-gradle-installation-01.json'
assert not receipt_path.exists()
try:
    registry_path.write_bytes(registry_after)
    gradle_path.write_bytes(gradle_after)
    assert registry_path.read_bytes() == registry_after and gradle_path.read_bytes() == gradle_after
except BaseException:
    registry_path.write_bytes(registry_before)
    gradle_path.write_bytes(gradle_before)
    raise
receipt = dict(schema=1, candidateBaseHead=plan['candidateHead'], readonlyPlanSHA256Bytes=sha(plan_bytes),
               sourceRegistryCountBefore=1170, sourceRegistryCountAfter=1172, resourceCount=213,
               existingIdentityUnionsApplied=len(union_paths), newIdentities=len(plan['newIdentities']),
               addedGradleTasks=[row['taskName'] for row in plan['gradleAppends']],
               dependenciesAdded=False, registryModeOrSHAChanged=False, existingGradlePrefixUnchanged=True,
               installerSHA256Bytes=sha(Path(__file__).read_bytes()),
               files=[dict(path='desktop/upstream-sources.json', beforeSHA256Bytes=sha(registry_before), afterSHA256Bytes=sha(registry_after)),
                      dict(path='desktop/build.gradle.kts', beforeSHA256Bytes=sha(gradle_before), afterSHA256Bytes=sha(gradle_after))],
               wholeBuildPassed=False, actualMainAccepted=False, newExeDeployed=False)
receipt_path.write_bytes((json.dumps(receipt, indent=2) + '\n').encode())
print(json.dumps(dict(sourceRegistryCount=1172, resourceCount=213, existingIdentityUnions=19, newIdentities=2, newGradleTasks=4)))
