from pathlib import Path
import hashlib
import json

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = MAIN / 'desktop/.local/stable-video-repository-core-ports-parity'
OUT = REPO / 'desktop/verification/stable-video-repository-core-ports'
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
assert sha(raw) == 'c9128187e1828c0b18a1f1c776d3c3b4f900751bf676f2edbfcbefd628e4437e'
put('prepared/frozen-handoff.json', raw)
packet = json.loads(raw)['rawArtifacts']
assert len(packet) == 41
for row in packet:
    data = read(LANE / row['path'])
    assert sha(data) == row['sha256Bytes'] and len(data) == row['bytes']
    register('prepared/' + row['path'], data)
for name in ['video-repository-core-install55', 'actual55-repository-fixture01']:
    folder = HERE / name
    for p in sorted(wide(folder).rglob('*'), key=str):
        if p.is_file():
            register('root/' + name + '/' + p.relative_to(wide(folder)).as_posix(), p.read_bytes())
for name in ['manifest.json', 'ordered-runtime-cp.json']:
    put('root/snapshot55/' + name, read(MAIN / 'desktop/.local/stable-product-snapshot-55' / name))
for name in ['classes-55.log', 'classpath-55.log']:
    put('root/' + name, read(REPO / 'desktop/.local/stable-build-repair' / name))
startup_folder = HERE / 'physical-root-startup-55-attempt01'
for p in sorted(wide(startup_folder).iterdir(), key=str):
    if p.is_file() and not p.name.startswith('private-'):
        register('root/physical-startup55/' + p.name, p.read_bytes())
for name in ['install-video-repository-core55.py', 'run-actual55-repository-fixture.py',
    'freeze-video-repository-core55.py', 'jvm-method-name-audit-55.json']:
    put('root/' + name, read(HERE / name))
startup = json.loads(read(startup_folder / 'result.json'))
assert startup['passed'] and startup['productionOverrides'] == 0 and startup['processExitCode'] == 0
assert not read(startup_folder / 'stderr.raw.log')
assert json.loads(read(HERE / 'jvm-method-name-audit-55.json'))['issueCount'] == 0
fixture = json.loads(read(HERE / 'actual55-repository-fixture01/runner-result.json'))
assert fixture['passed'] and fixture['productionOverrides'] == 0 and len(fixture['byteVerifiedProductOrigins']) == 7
report = dict(phase=55, sourceIdentityCount=1056, resourceRegistryCount=213, runtimeEntries=101,
    wholeClassesPassed=True, wholeTestSourcesCompiled=True, illegalJvmMethodNames=0,
    sameRepositoryCacheWbiBridgeIntegrated=True, newCaches=0, newClients=0, newDependencies=0,
    originalCachePolicy=dict(capacity=80, lifetimeMinutes=10, expiry='strict greater than'),
    sameWbiLifetimeMinutes=30, sameStoreEpochAndPlaybackAuthorizationGuardsPreserved=True,
    installedProductFixtureAssertions=27, installedProductFixture=fixture,
    unchangedMainStartupAndNormalClose=startup, fullOrdinaryVideoRootMountAccepted=False,
    ownedTvRefreshTailInstalled=False, currentNativeAuthorityRetired=False, newExeDeployed=False,
    sourceInventoryIsNotFunctionalOrReusePercentage=True)
put('integration-report.json', (json.dumps(report, indent=2) + '\n').encode())
raw = (json.dumps(dict(artifacts=rows, excludedRebuildableOutputs=excluded), indent=2) + '\n').encode()
wide(OUT / 'artifact-manifest.json').write_bytes(raw)
wide(OUT / 'README.md').write_text('''Same Repository raw load, cache and WBI integration
==================================================

Stable v0.2.3 stays pinned at3d5d19a. One required request binding projects the original raw protocol onto the existing Repository, Store, owned Call.Factory, playbackCache and WBI fields. It creates no second cache, account or HTTP client. Five exact Repository hunks and three sole-cache hunks apply original capacity80/ten-minute strict expiry/30-minute WBI timing while preserving Windows epoch, receipt revision, codec/audio and clock-rollback isolation. The PlayUrlCache original identity is registered as the source of the selected policy; no PlayUrlCache object is generated.

Whole classes and test sources55 compile with1056 original source identities,213 resources,101 runtime entries and no illegal JVM method names. The unchanged27-assertion fixture now runs against only the actual55 graph with zero product overrides. All seven reported class origins are matched byte-for-byte to actual product JARs. Its old descriptive scope text is retained in the unchanged fixture result; the Root runner records the actual no-override graph. Checks use an isolated actual Store and socket-free API fixtures, not an authenticated network or native playback. Actual unchanged Main also starts and closes normally with empty stderr.

The complete ordinary-video owner is not constructed here. Each future load must capture once inside its actual request coroutine; cancelled/completed requests cannot lend their binding to a later load. The existing TV refresh authority still needs its explicit receipt/job/entry-owned adapter, and full VM/StateHolder/media adoption remains separate. The old controller remains active until its one-authority transition is implemented. No EXE is replaced by this source milestone. Source counts do not represent functional parity or reuse percentage.
''', encoding='utf8', newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows), excludedBinaries=len(excluded), manifestSha256Bytes=sha(raw))))
