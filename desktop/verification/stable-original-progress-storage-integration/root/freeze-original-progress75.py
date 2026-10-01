from pathlib import Path
import hashlib,json
H = Path(__file__).resolve().parent; MAIN = H.parents[2]; REPO = MAIN.parent/'BiliPai-v023'
OUT = REPO/'desktop/verification/stable-original-progress-storage-integration'
assert not (OUT/'artifact-manifest.json').exists()

def wide(p):
    s = str(Path(p).absolute()); prefix = chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def read(p): return wide(p).read_bytes()
def sha(b): return hashlib.sha256(b).hexdigest()
rows = []
def put(name,b):
    p = wide(OUT/name); p.parent.mkdir(parents=True,exist_ok=True)
    if p.exists(): assert p.read_bytes() == b, name
    else: p.write_bytes(b)
    rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))

LANE = MAIN/'desktop/.local/stable-original-progress-storage-parity'
raw = read(LANE/'frozen-handoff.json'); assert sha(raw) == 'ccb08af8a9ce7e887eb87c0634cad273d918c0b61ee2020d963e77c677f3d551'
packet = json.loads(raw); assert len(packet['artifacts']) == 34
for row in packet['artifacts']:
    b = read(LANE/row['path']); assert sha(b) == row['sha256Bytes'] and len(b) == row['bytes']
    put('prepared/'+row['path'],b)
put('prepared/frozen-handoff.json',raw)
installedRoot = H/'original-progress-install75'; installed = json.loads(read(installedRoot/'installed.json'))
assert installed['newManual'] == 1 and installed['exactProducerHunks'] == 2 and installed['sourceIdentityCount'] == 1170
for p in sorted(wide(installedRoot).rglob('*')):
    if p.is_file(): put('root/install/'+p.relative_to(wide(installedRoot)).as_posix(),p.read_bytes())
S = MAIN/'desktop/.local/stable-product-snapshot-75'; manifest = read(S/'manifest.json')
assert sha(manifest) == '548d3f0d96db04be54e2e22d4e2d42ebca3a83c806fd6fa55d400f83c7d4f1b9'
meta = json.loads(manifest); assert meta['runtimeEntries'] == 101 and meta['sourceRegistryCount'] == 1170
pins = {row['path']:row['sha256Bytes'] for row in meta['inputs']}
for target in installed['targets']:
    assert sha(read(REPO/target['path'])) == target['afterSha256Bytes']
    if target['path'] != 'desktop/upstream-sources.json': assert pins[target['path']] == target['afterSha256Bytes']
cp = read(S/'ordered-runtime-cp.json')
assert sha(cp) == '4f2f3212c1ca85bc2bc6208f834bc6bc34b10c533d56da904139918dd5a2253a'
for row in json.loads(cp): assert sha(read(row['path'])) == row['sha256Bytes']
put('root/snapshot75/manifest.json',manifest); put('root/snapshot75/ordered-runtime-cp.json',cp)
for name in ['classes-75.log','classpath75.log']:
    b = read(REPO/'desktop/.local/stable-build-repair'/name)
    assert b'BUILD SUCCESSFUL' in b and b'BUILD FAILED' not in b; put('root/'+name,b)
put('root/classes-75-preflight.log',read(REPO/'desktop/.local/stable-build-repair/classes-75-preflight.log'))
audit = read(H/'jvm-method-name-audit-75.json'); result = json.loads(audit)
assert result['classCount'] == 15310 and result['issueCount'] == 0
put('root/jvm-method-name-audit-75.json',audit)
for name in ['install-original-progress75.py','freeze-original-progress75.py']: put('root/'+name,read(H/name))
PROOF = MAIN/'desktop/.local/stable-original-progress-actual75-proof'
proof = json.loads(read(PROOF/'runs/01/result.json'))
assert proof['passed'] and proof['assertions'] == 22 and proof['productionOverrides'] == 0
assert proof['uniqueProductClassOrigins'] == 4 and proof['pinsUnchanged']
assert read(PROOF/'runs/01/inputs/ProgressStorageProof.kt') == read(LANE/'ProgressStorageProof.kt')
assert json.loads(read(PROOF/'runs/01/overlap.json'))['productClassOverlap'] == []
excluded = []
for file in sorted(wide(PROOF).rglob('*')):
    if not file.is_file(): continue
    relative = file.relative_to(wide(PROOF)).as_posix(); b = file.read_bytes()
    if file.suffix in ['.jar','.class','.pyc']:
        excluded.append(dict(path=relative,sizeBytes=len(b),sha256Bytes=sha(b),reason='Rebuildable fixture output; not production payload.'))
    else: put('root/installed-proof/'+relative,b)
put('root/installed-proof/excluded-artifacts.json',(json.dumps(excluded,indent=2)+'\n').encode())
report = dict(phase=75,preparedRaw=34,newManual=1,exactProducerHunks=2,newSourceIdentityCount=1,
    sourceIdentityCount=1170,resourceCount=213,newDependencyCount=0,wholeWindowsClassesPassed=True,
    kotlinClasses=15310,illegalJvmMethodNames=0,installedRuntimeEntries=101,installedProgressAssertions=22,
    productionOverrides=0,uniqueInstalledProductClassOrigins=4,originalManagerBodyUnchanged=True,
    sameActualGlobalStoreDiskAndImmediateMemoryVerified=True,globalRootProgressMounted=False,
    completeRootVMHolderMainAccepted=False,nativeWindowAccountAccepted=False,desktopExeReplaced=False,
    initialPreflightFailure='The first Gradle command was mistakenly invoked in the Android repository root. '
        'No Windows compile started. The desktop standalone project then passed classes in 1m16s.')
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
manifestData = (json.dumps(dict(artifacts=rows,excludedRebuildableOutputs=excluded),indent=2)+'\n').encode()
assert len({row['path'] for row in rows}) == len(rows)
assert {p.relative_to(wide(OUT)).as_posix() for p in wide(OUT).rglob('*') if p.is_file()} == {row['path'] for row in rows}
wide(OUT/'artifact-manifest.json').write_bytes(manifestData)
wide(OUT/'README.md').write_text('''The complete pinned original PlaybackProgressManager is generated by the existing sole full-owner producer. A single platform file bridges only its consumed Long/editor preferences onto the same global PluginStore, preserving the original immediate memory cache and queueing atomic disk replacements outside playback admissions. The app owns one writer/Manager; accepted edits must drain before Store freeze, restore/restart or app-scope cancellation.

Installation adds one manual source, two exact producer hunks and one original source identity. Existing registry rows/resources are preserved. Whole Windows classes pass in 1m16s; the immutable actual75 snapshot has 101 runtime entries, 1170 original identities, 213 resources and no illegal method names across 15310 Kotlin classes. The mistaken repository-root Gradle preflight log is retained separately from the successful desktop compile.

The installed proof compiles only the exact frozen fixture with zero product overrides. Four exercised production classes have unique actual75 application-JAR origins. All 22 checks pass using the real temp-disk PluginStore: immediate reads during a blocked backing write, milliseconds/CID/BVID fallback, 5000 ms threshold, strictly greater than 95 percent completion, 4096 eviction, serial disk drain, unrelated namespace preservation and rejected writes after closing. All 101 entries remain pinned before and after.

This proves installed progress/storage behavior. It does not yet mount the app-global progress owner into the complete Root player, prove its normal shutdown path, native/window/account behavior, or replace the desktop EXE. Prepared prospective and failed histories retain their original labels. Fixture JARs and task-only private temp Store data are not production payloads.
''',encoding='utf-8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),manifestSha256=sha(manifestData),installedAssertions=22,productionOverrides=0)))
