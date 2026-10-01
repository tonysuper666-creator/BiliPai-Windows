from pathlib import Path
import hashlib
import json

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = MAIN / 'desktop/.local/stable-video-invocation-parity'
OUT = REPO / 'desktop/verification/stable-video-playback-invocation'
assert not OUT.exists()
sha = lambda b: hashlib.sha256(b).hexdigest()
rows, excluded = [], []

def wide(p):
    s = str(Path(p).absolute())
    prefix = chr(92) * 2 + '?' + chr(92)
    return Path(s if s.startswith(prefix) else prefix + s)

def read(p):
    return wide(p).read_bytes()

def put(name, data):
    p = wide(OUT / name)
    p.parent.mkdir(parents=True, exist_ok=True)
    if p.exists():
        assert p.read_bytes() == data, name
        return
    p.write_bytes(data)
    rows.append(dict(path=name, sha256Bytes=sha(data), sizeBytes=len(data)))

def register(name, data):
    if Path(name).suffix.lower() in ('.class', '.jar', '.kotlin_module', '.pyc', '.png', '.jpg', '.mp4', '.dll'):
        excluded.append(dict(path=name, sha256Bytes=sha(data), reason='Rebuildable binary or private rendered output'))
    else:
        put(name, data)

raw = read(LANE / 'frozen-handoff.json')
assert sha(raw) == '6a3b07d40a24f7cddf3d5ced277cf6b1fa4c410877bb358294cada770c59fddf'
put('prepared/frozen-handoff.json', raw)
packet = json.loads(raw)['files']
assert len(packet) == 17
for row in packet:
    data = read(LANE / row['path'])
    assert sha(data) == row['sha256Bytes'] and len(data) == row['bytes']
    register('prepared/' + row['path'], data)
for name in ['video-invocation-install57', 'actual57-invocation-fixture01']:
    folder = HERE / name
    for p in sorted(wide(folder).rglob('*'), key=str):
        if p.is_file():
            register('root/' + name + '/' + p.relative_to(wide(folder)).as_posix(), p.read_bytes())
for name in ['manifest.json', 'ordered-runtime-cp.json']:
    put('root/snapshot57/' + name, read(MAIN / 'desktop/.local/stable-product-snapshot-57' / name))
for name in ['classes-57.log', 'classpath-57.log']:
    put('root/' + name, read(REPO / 'desktop/.local/stable-build-repair' / name))
startup_folder = HERE / 'physical-root-startup-57-attempt01'
for p in sorted(wide(startup_folder).iterdir(), key=str):
    if p.is_file() and not p.name.startswith('private-'):
        register('root/physical-startup57/' + p.name, p.read_bytes())
for name in ['install-video-invocation57.py', 'run-actual57-invocation-fixture.py',
    'freeze-video-invocation57.py', 'jvm-method-name-audit-57.json']:
    put('root/' + name, read(HERE / name))
startup = json.loads(read(startup_folder / 'result.json'))
assert startup['passed'] and startup['productionOverrides'] == 0 and startup['processExitCode'] == 0
assert not read(startup_folder / 'stderr.raw.log')
assert json.loads(read(HERE / 'jvm-method-name-audit-57.json'))['issueCount'] == 0
fixture = json.loads(read(HERE / 'actual57-invocation-fixture01/runner-result.json'))
assert fixture['passed'] and fixture['productionOverrides'] == 0 and len(fixture['pinnedProductOrigins']) == 5
report = dict(phase=57, sourceIdentityCount=1057, resourceRegistryCount=213, runtimeEntries=101,
    wholeClassesPassed=True, wholeTestSourcesCompiled=True, illegalJvmMethodNames=0,
    originalPersistentUseCaseRequestContextInstalled=True, newCaches=0, newClients=0, newDependencies=0,
    actualRequestCapturesLiveJob=True, nestedChildrenShareImmutableInvocation=True,
    completedRequestCannotProvideAcceptedRecovery=True, acceptedRecoveryRequiresIndependentPublication=True,
    installedProductFixtureAssertions=16, installedProductFixture=fixture,
    unchangedMainStartupAndNormalClose=startup, fullOrdinaryVideoRootMountAccepted=False,
    currentNativeAuthorityRetired=False, newExeDeployed=False,
    sourceInventoryIsNotFunctionalOrReusePercentage=True)
put('integration-report.json', (json.dumps(report, indent=2) + '\n').encode())
raw = (json.dumps(dict(artifacts=rows, excludedRebuildableOutputs=excluded), indent=2) + '\n').encode()
wide(OUT / 'artifact-manifest.json').write_bytes(raw)
wide(OUT / 'README.md').write_text("""Original persistent playback UseCase request context
====================================================

Stable v0.2.3 remains pinned at3d5d19a. One required invocation facade forwards the original persistent UseCase to the same installed Repository, raw binding and media port. Each actual launched operation captures its own live Job and immutable authorization receipt. Original parallel children and dispatcher changes inherit that operation; a per-instance ThreadContextElement only supplies its synchronous view and restores it after each continuation. There is no mutable latest-request binding. Context-free recovery must obtain the independently admitted actual accepted source; completed load bindings cannot authorize it.

Whole classes and test sources57 compile with1057 original source identities,213 resources and101 runtime entries; the static audit finds no illegal JVM method names among13543 classes. The unchanged16-assertion fixture passes on only actual57 production artifacts, with zero product overrides and no fixture/product class collisions. All five reported production class origins resolve to pinned product JARs, and their class entry hashes are recorded. The fixture does not report loaded class byte hashes. Its media acceptance is an observer over real Store/receipt admission, not MPV/HWND or source handoff. Tests cover interleaved requests, parallel children, dispatcher changes, nested capture reuse, completed/canceled request rejection, independent accepted-source recovery and facade closure. Actual unchanged Main starts and closes normally with empty stderr.

This packet constructs no complete ordinary-video VM/StateHolder or actual owner factory and does not retire the existing Controller. Required synchronous status and accepted-source publication remain supplied by the future one-owner assembly. No EXE is replaced. Source counts do not represent functional completion or reuse percentage. Earlier compile and fixture failures remain in immutable prepared history.
""",encoding='utf8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows), excludedBinaries=len(excluded), manifestSha256Bytes=sha(raw))))
