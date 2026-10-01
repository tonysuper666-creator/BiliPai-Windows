from pathlib import Path
import hashlib
import json

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = HERE / 'native-publication-adoption58'
OUT = REPO / 'desktop/verification/stable-native-publication-adoption'
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
assert delta['candidateLFsha256'] == sha(read(REPO / delta['sourcePath']).replace(b'\r\n', b'\n'))
for folder_name, folder in [('source-packet', LANE), ('actual58-native-publication-fixture01', HERE / 'actual58-native-publication-fixture01')]:
    for p in sorted(wide(folder).rglob('*'), key=str):
        if p.is_file():
            register('root/' + folder_name + '/' + p.relative_to(wide(folder)).as_posix(), p.read_bytes())
for name in ['manifest.json', 'ordered-runtime-cp.json']:
    put('root/snapshot58/' + name, read(MAIN / 'desktop/.local/stable-product-snapshot-58' / name))
for name in ['classes-58.log', 'classpath-58.log']:
    put('root/' + name, read(REPO / 'desktop/.local/stable-build-repair' / name))
for name in ['run-actual58-native-publication-fixture.py', 'freeze-native-publication58.py', 'jvm-method-name-audit-58.json']:
    put('root/' + name, read(HERE / name))
assert json.loads(read(HERE / 'jvm-method-name-audit-58.json'))['issueCount'] == 0
fixture = json.loads(read(HERE / 'actual58-native-publication-fixture01/runner-result.json'))
proof = json.loads(read(HERE / 'actual58-native-publication-fixture01/result.json'))
assert fixture['passed'] and fixture['productionOverrides'] == 0 and len(fixture['byteVerifiedProductOrigins']) == 3
assert proof['passed'] and proof['assertions'] == 40
report = dict(phase=58, sourceIdentityCount=1057, resourceRegistryCount=213, runtimeEntries=101,
    wholeClassesPassed=True, wholeTestSourcesCompiled=True, illegalJvmMethodNames=0,
    sameExistingMpvActorBarrierInstalled=True, sameSourcePublicationAdoptionInstalled=True,
    nativeReloadForPublicationAdoption=False, nativeVersionPositionPauseSubtitlesCanvasRetained=True,
    expectedPublicationIdentityPreventsSameVersionStaleOverwrite=True,
    actorBarrierWaitRequiresNoStoreOrEntryLocks=True, adoptionRequiresCallerStoreEntryAdmission=True,
    newPlayers=0, newDependencies=0, actualNativeFixtureAssertions=40, installedProductFixture=fixture,
    localClipOnly=True, realAccountUsed=False, fullOrdinaryVideoRootMountAccepted=False,
    fullControllerDrainAccepted=False, independentVisualAcceptance=False, newExeDeployed=False,
    sourceInventoryIsNotFunctionalOrReusePercentage=True)
put('integration-report.json', (json.dumps(report, indent=2) + '\n').encode())
raw = (json.dumps(dict(artifacts=rows, excludedRebuildableOutputs=excluded), indent=2) + '\n').encode()
wide(OUT / 'artifact-manifest.json').write_bytes(raw)
wide(OUT / 'README.md').write_text("""Retain the native source while adopting a new publication
========================================================

The existing MpvPlayer gains two internal ownership-transfer support methods. drainSourceCommands acknowledges earlier commands on its same native actor, checking source version and playback revision, with a three-second timeout. It issues no media command and must be awaited outside Store/entry locks after old producer jobs are cancelled and joined. adoptPublication runs under the caller's Store/entry admission and the existing short native lock. It requires actual ready codec/pause readback and rejects loading, failed, ended, missing or foreign sources. Both expected and replacement must match the actual URL, audio, headers, metadata, segments and authorization. Actual old publication object identity prevents a stale second handoff from overwriting a new owner at the same version. Only nativePublication changes; retained start intent, native version/revision, clock, tracks, state and Canvas remain intact. No loadfile, player, account or cache is created.

Whole classes and test sources58 pass with1057 original identities,213 resources and101 runtime entries. The static audit finds no illegal JVM method names among13546 classes. A Root fixture uses only actual58 product artifacts and the pinned local sample clip/DLL. Forty checks pass against a real native window/player, with zero product overrides and three loaded class byte hashes verified against product JARs. It establishes actual native pause, a tracked seek to four seconds,1.25 speed and an external subtitle before transfer; checks both expected and replacement mismatch paths for ten source fields; adopts once; rejects a stale overwrite; and confirms settled position, pause, seek receipt, speed, subtitle control and Canvas continuity. Native actor drain and closed/foreign guards also pass. Runtime/compiler/source/clip/DLL pins match before and after. The fixture closes its own player and window normally.

This is targeted native API verification using explicit local fixture publications. It does not implement the full Controller drain or construct/mount the complete ordinary-video VM/StateHolder, authenticate an account, or independently accept visual UI behavior. Existing unchanged Main startup was last checked at actual57. No EXE is replaced by this source milestone. Source counts do not measure functional completion or reuse percentage.
""",encoding='utf8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows), excludedBinaries=len(excluded), manifestSha256Bytes=sha(raw))))
