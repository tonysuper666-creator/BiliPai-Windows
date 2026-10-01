from pathlib import Path
import hashlib
import json

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = MAIN / 'desktop/.local/stable-video-owned-token-refresh-parity'
OUT = REPO / 'desktop/verification/stable-video-owned-token-refresh'
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
assert sha(raw) == '8725f0374227e10d4bb716a2d847bdef72be0fbbd90dd45848f6e3fbb3e5202c'
put('prepared/frozen-handoff.json', raw)
packet = json.loads(raw)['rawArtifacts']
assert len(packet) == 58
for row in packet:
    data = read(LANE / row['path'])
    assert sha(data) == row['sha256Bytes'] and len(data) == row['bytes']
    register('prepared/' + row['path'], data)
for name in ['video-owned-token-install56', 'actual56-token-refresh-fixture01']:
    folder = HERE / name
    for p in sorted(wide(folder).rglob('*'), key=str):
        if p.is_file():
            register('root/' + name + '/' + p.relative_to(wide(folder)).as_posix(), p.read_bytes())
for name in ['manifest.json', 'ordered-runtime-cp.json']:
    put('root/snapshot56/' + name, read(MAIN / 'desktop/.local/stable-product-snapshot-56' / name))
for name in ['classes-56.log', 'classpath-56.log']:
    put('root/' + name, read(REPO / 'desktop/.local/stable-build-repair' / name))
startup_folder = HERE / 'physical-root-startup-56-attempt01'
for p in sorted(wide(startup_folder).iterdir(), key=str):
    if p.is_file() and not p.name.startswith('private-'):
        register('root/physical-startup56/' + p.name, p.read_bytes())
for name in ['install-video-owned-token56.py', 'run-actual56-token-refresh-fixture.py',
    'freeze-video-owned-token56.py', 'jvm-method-name-audit-56.json']:
    put('root/' + name, read(HERE / name))
startup = json.loads(read(startup_folder / 'result.json'))
assert startup['passed'] and startup['productionOverrides'] == 0 and startup['processExitCode'] == 0
assert not read(startup_folder / 'stderr.raw.log')
assert json.loads(read(HERE / 'jvm-method-name-audit-56.json'))['issueCount'] == 0
fixture = json.loads(read(HERE / 'actual56-token-refresh-fixture01/runner-result.json'))
assert fixture['passed'] and fixture['productionOverrides'] == 0 and len(fixture['byteVerifiedProductOrigins']) == 5
report = dict(phase=56, sourceIdentityCount=1057, resourceRegistryCount=213, runtimeEntries=101,
    wholeClassesPassed=True, wholeTestSourcesCompiled=True, illegalJvmMethodNames=0,
    sameRepositoryCacheWbiBridgeIntegrated=True, newCaches=0, newClients=0, newDependencies=0,
    originalCachePolicy=dict(capacity=80, lifetimeMinutes=10, expiry='strict greater than'),
    sameWbiLifetimeMinutes=30, sameStoreEpochAndPlaybackAuthorizationGuardsPreserved=True,
    installedProductFixtureAssertions=25, installedProductFixture=fixture,
    unchangedMainStartupAndNormalClose=startup, fullOrdinaryVideoRootMountAccepted=False,
    ownedTvRefreshTailInstalled=True, currentNativeAuthorityRetired=False, newExeDeployed=False,
    sourceInventoryIsNotFunctionalOrReusePercentage=True)
put('integration-report.json', (json.dumps(report, indent=2) + '\n').encode())
raw = (json.dumps(dict(artifacts=rows, excludedRebuildableOutputs=excluded), indent=2) + '\n').encode()
wide(OUT / 'artifact-manifest.json').write_bytes(raw)
wide(OUT / 'README.md').write_text("""Same primary TV token refresh integration
=========================================

Stable v0.2.3 remains pinned at3d5d19a. The required token-refresh binding uses the existing primary login actor, TV request protocol, Repository, validation transport and SessionStore. The original non-TV behavior returns false without refresh. A dedicated playback account prevents primary token refresh. No second account, client, cache, player or dependency is created. HTTP and actor waits stay outside the Store/entry commit gates; final installation requires the captured authorization receipt, active caller Job and current entry. Successful credential replacement retires the old raw-load receipt without stopping the current native owner.

Whole classes and test sources56 pass with1057 source identities,213 resources and101 runtime entries. Static classfile audit finds no illegal JVM method names among13527 classes. The unchanged original25-assertion fixture runs on only actual56 production artifacts, with zero product overrides and no fixture/product class collisions. All five reported production class origins are byte-verified. Its historical prepared-scope text remains unchanged; the Root runner identifies the actual graph. These checks use an isolated actual Store and memory-only Retrofit transports, with no external sockets, account or native playback. Actual unchanged Main starts and closes normally with empty stderr; runtime and native asset pins remain unchanged.

Full ordinary-video VM/StateHolder construction and one-authority media adoption remain outstanding. This packet does not retire the existing Controller or replace the desktop EXE. Source identity counts do not measure functional completion or source reuse percentage. Earlier failed fixture attempts are preserved in the prepared raw packet.
""", encoding='utf8', newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows), excludedBinaries=len(excluded), manifestSha256Bytes=sha(raw))))
