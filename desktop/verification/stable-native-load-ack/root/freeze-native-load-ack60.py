from pathlib import Path
import hashlib
import json

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = HERE / 'native-load-ack60'
OUT = REPO / 'desktop/verification/stable-native-load-ack'
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
for folder_name, folder in [('source-packet', LANE), ('actual60-native-load-ack-fixture01', HERE / 'actual60-native-load-ack-fixture01')]:
    for p in sorted(wide(folder).rglob('*'), key=str):
        if p.is_file():
            register('root/' + folder_name + '/' + p.relative_to(wide(folder)).as_posix(), p.read_bytes())
for name in ['manifest.json', 'ordered-runtime-cp.json']:
    put('root/snapshot60/' + name, read(MAIN / 'desktop/.local/stable-product-snapshot-60' / name))
for name in ['classes-60.log', 'classpath-60.log']:
    put('root/' + name, read(REPO / 'desktop/.local/stable-build-repair' / name))
for name in ['run-actual60-native-load-ack-fixture.py', 'freeze-native-load-ack60.py', 'jvm-method-name-audit-60.json']:
    put('root/' + name, read(HERE / name))
assert json.loads(read(HERE / 'jvm-method-name-audit-60.json'))['issueCount'] == 0
fixture = json.loads(read(HERE / 'actual60-native-load-ack-fixture01/runner-result.json'))
proof = json.loads(read(HERE / 'actual60-native-load-ack-fixture01/result.json'))
assert fixture['passed'] and fixture['productionOverrides'] == 0 and len(fixture['byteVerifiedProductOrigins']) == 2
assert proof['passed'] and proof['assertions'] == 13
sam = json.loads(read(HERE / 'actual60-native-load-ack-fixture01/sam-signature-result.json'))
assert sam['passed'] and sam['singleAbstractMethod'] and sam['hookIsJvmDefault']
report = dict(phase=60, sourceIdentityCount=1057, resourceRegistryCount=213, runtimeEntries=101,
    wholeClassesPassed=True, wholeTestSourcesCompiled=True, illegalJvmMethodNames=0,
    actualNativeLoadAckInstalled=True, defaultHookPreservesSingleAbstractMethod=True,
    ackOnlyAfterSuccessfulLoadfileAndEntryReadback=True, staleUnitNoOpDoesNotAck=True,
    rejectedPublicationDoesNotAck=True, adoptionAndBarrierDoNotAck=True,
    callbackMayOnlySetAtomicFlag=True, newPlayers=0, newDependencies=0,
    actualNativeFixtureAssertions=13, installedProductFixture=fixture, samSignature=sam,
    localClipOnly=True, fixtureLatchOutsideMpvLock=True, realAccountUsed=False,
    fullOrdinaryVideoRootMountAccepted=False, fullControllerDrainAccepted=False,
    independentVisualAcceptance=False, newExeDeployed=False,
    sourceInventoryIsNotFunctionalOrReusePercentage=True)
put('integration-report.json', (json.dumps(report, indent=2) + '\n').encode())
raw = (json.dumps(dict(artifacts=rows, excludedRebuildableOutputs=excluded), indent=2) + '\n').encode()
wide(OUT / 'artifact-manifest.json').write_bytes(raw)
wide(OUT / 'README.md').write_text("""Acknowledge actual accepted native load commands
==============================================

The existing DesktopNativePlaybackPublication remains a single-abstract-method interface and gains a default no-op onLoadCommandAccepted hook. MPV invokes it only after its version/revision/session guard passes, loadfile succeeds and the actual playlist entry ID is read back. A stale Unit command can return normally without executing loadfile, so normal callback return alone is not an acceptance receipt. Rejected admission, stale no-op, read-only native barrier and source-publication adoption produce no ACK. Implementations must only set an atomic flag in this native-actor callback, with no business work, IO or waiting. This enables an initial request cancellation/generation guard to retire only after the actual command is accepted; the complete ordinary native owner is supplied separately.

Whole classes and test sources60 pass with1057 original identities,213 resources and101 runtime entries. The static audit finds no illegal JVM method names among13551 classes. javap verifies one abstract admit method and a JVM default ACK hook. Thirteen checks use only actual60 production artifacts and the same pinned local clip/DLL, with zero product overrides and two loaded class byte hashes matched to product JARs. A fixture latch outside the MPV lock deliberately pauses an old admission, replaces the source version and releases its stale Unit command; normal return produces no ACK, while the real replacement load receives exactly one. Rejected publication is not loaded or acknowledged. Existing SAM publication still plays, adoption/barrier issue no ACK, and explicit real replay receives one ACK while retaining its source version. Runtime/compiler/source/clip/DLL pins match before and after. The fixture closes its own window/player normally.

The latch is fault scheduling in a local native API fixture, not an implementation pattern for production admission. No account/Store/HTTP or full ordinary page/controller migration is exercised here. No EXE is replaced. Source counts do not measure functional completion or reuse percentage.
""",encoding='utf8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows), excludedBinaries=len(excluded), manifestSha256Bytes=sha(raw))))
