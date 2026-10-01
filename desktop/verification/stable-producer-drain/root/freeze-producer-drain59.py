from pathlib import Path
import hashlib
import json

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = HERE / 'producer-drain59'
OUT = REPO / 'desktop/verification/stable-producer-drain'
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

delta = json.loads(read(LANE / 'source-delta.json'))
for item in delta['sources']:
    assert item['candidateLFsha256'] == sha(read(REPO / item['sourcePath']).replace(b'\r\n', b'\n'))
for folder_name, folder in [('source-packet', LANE), ('actual59-producer-drain-fixture01', HERE / 'actual59-producer-drain-fixture01')]:
    for p in sorted(wide(folder).rglob('*'), key=str):
        if p.is_file():
            register('root/' + folder_name + '/' + p.relative_to(wide(folder)).as_posix(), p.read_bytes())
for name in ['manifest.json', 'ordered-runtime-cp.json']:
    put('root/snapshot59/' + name, read(MAIN / 'desktop/.local/stable-product-snapshot-59' / name))
for name in ['classes-59.log', 'classpath-59.log']:
    put('root/' + name, read(REPO / 'desktop/.local/stable-build-repair' / name))
for name in ['run-actual59-producer-drain-fixture.py', 'freeze-producer-drain59.py', 'jvm-method-name-audit-59.json']:
    put('root/' + name, read(HERE / name))
assert json.loads(read(HERE / 'jvm-method-name-audit-59.json'))['issueCount'] == 0
fixture = json.loads(read(HERE / 'actual59-producer-drain-fixture01/runner-result.json'))
proof = json.loads(read(HERE / 'actual59-producer-drain-fixture01/result.json'))
assert fixture['passed'] and fixture['productionOverrides'] == 0 and len(fixture['byteVerifiedProductOrigins']) == 2
assert proof['passed'] and proof['assertions'] == 15
report = dict(phase=59, sourceIdentityCount=1057, resourceRegistryCount=213, runtimeEntries=101,
    wholeClassesPassed=True, wholeTestSourcesCompiled=True, illegalJvmMethodNames=0,
    sameExistingHeartbeatReporterJoinInstalled=True, sameExistingAutomaticSubtitleJoinInstalled=True,
    originalCloseSemanticsPreserved=True, joinsAwaitedOutsideStoreEntrySubtitleLocks=True,
    timeoutReturnsFalseAndProhibitsReplacementProducer=True, noGlobalAssetsClosed=True,
    newPlayers=0, newProducers=0, newDependencies=0, fixtureAssertions=15, installedProductFixture=fixture,
    memoryWorkOnly=True, realAccountUsed=False, fullOrdinaryVideoRootMountAccepted=False,
    fullControllerDrainAccepted=False, newExeDeployed=False,
    sourceInventoryIsNotFunctionalOrReusePercentage=True)
put('integration-report.json', (json.dumps(report, indent=2) + '\n').encode())
raw = (json.dumps(dict(artifacts=rows, excludedRebuildableOutputs=excluded), indent=2) + '\n').encode()
wide(OUT / 'artifact-manifest.json').write_bytes(raw)
wide(OUT / 'README.md').write_text("""Confirm old heartbeat and subtitle producers have joined
========================================================

The same existing DesktopHeartbeatReporter and DesktopAutomaticSubtitles each gain an internal suspend closeAndJoin method. Their original close logic remains unchanged. The method awaits the actual existing scope Job outside Store/entry/subtitle locks and returns true only when that scope has completed. Reporter joining allows its existing close timeout plus one second for cancellation to settle; subtitle joining is bounded at three seconds. An uncooperative task returns false, so a caller must not start a replacement producer. External caller cancellation is propagated. The shared Root-owned subtitle asset files and current MPV tracks are not closed or removed by these support methods.

Whole classes and test sources59 pass with1057 original identities,213 resources and101 runtime entries. The static audit finds no illegal JVM method names among13550 classes. Fifteen checks use only actual59 production artifacts, zero product overrides and memory-only injected work. Both loaded producer class byte hashes match product JARs. Checks verify actual in-flight worker joining, original final-report queue clearing, idempotent closure, refusal of new work, stale-epoch acknowledgement/request suppression, bounded false for uncooperative heartbeat/subtitle work, later true after that same work exits, and cancellation before any subtitle import or native installation. Runtime/compiler/source pins match before and after.

This supports a future one-owner Controller transfer. It does not perform the full Controller drain, construct/mount the complete ordinary-video VM/StateHolder, or authenticate/network/play a native source in this fixture. No EXE is replaced. Source counts do not measure functional completion or reuse percentage.
""",encoding='utf8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows), excludedBinaries=len(excluded), manifestSha256Bytes=sha(raw))))
