from pathlib import Path
import hashlib
import json
import sys

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
OUT = REPO / 'desktop/verification/stable-video-core-section-native'
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
    if Path(name).suffix.lower() in ('.class', '.jar', '.kotlin_module', '.pyc', '.png', '.jpg', '.mp4', '.dll', '.zip'):
        excluded.append(dict(path=name, sha256Bytes=sha(data), reason='Rebuildable binary, runtime dependency or private fixture media/render output'))
    else:
        put(name, data)

def packet(folder, prefix, filename, pin, key, count):
    raw = read(folder / filename)
    assert sha(raw) == pin
    put(prefix + '/' + filename, raw)
    items = json.loads(raw)[key]
    assert len(items) == count
    for item in items:
        path = Path(item['path'])
        if not path.is_absolute():
            path = folder / path
        data = read(path)
        assert sha(data) == item['sha256Bytes'], str(path)
        name = path.relative_to(folder).as_posix()
        register(prefix + '/' + name, data)

packet(MAIN / 'desktop/.local/stable-video-state-holder-parity', 'prepared/core', 'frozen-handoff.json',
    'b51df19b3c3bdc4507cc06ff7c3b7ffb1ef259152d503275c16f5ace43247377', 'artifacts', 192)
packet(MAIN / 'desktop/.local/stable-video-player-section-parity', 'prepared/section', 'frozen-section.json',
    'ffd4a0c0f4071c5ec58c9eecd09dadd1b14ea5579c571bebfb7663d478a1dd6b', 'rawArtifacts', 412)
packet(MAIN / 'desktop/.local/stable-windows-fullscreen-insets-audit', 'prepared/windows-insets', 'frozen-handoff.json',
    '6352e568dbfd5a17c7dc8c45df1496a65ba7e02e98c6900cf9fd3ef580d45007', 'rawArtifacts', 33)

native = MAIN / 'desktop/.local/stable-offline-native-integration-proof51'
native_descriptors = json.loads(sys.argv[1])
assert native_descriptors and all(r['name'].startswith('closed-actual54-') for r in native_descriptors)
for descriptor in native_descriptors:
    folder = native / descriptor['name']
    raw = read(folder / 'frozen-handoff.json')
    assert sha(raw) == descriptor['sha256Bytes']
    put('native/' + descriptor['name'] + '/frozen-handoff.json', raw)
    items = json.loads(raw)['rawArtifacts']
    assert len(items) == descriptor['count']
    for item in items:
        path = Path(item['path'])
        if not path.is_absolute():
            path = folder / path
        data = read(path)
        assert sha(data) == item['sha256Bytes']
        register('native/' + path.relative_to(native).as_posix(), data)

for p in sorted(wide(HERE / 'video-core-section-install54').rglob('*'), key=str):
    if p.is_file():
        register('root/install/' + p.relative_to(wide(HERE / 'video-core-section-install54')).as_posix(), p.read_bytes())
for name in ['manifest.json', 'ordered-runtime-cp.json']:
    put('root/snapshot54/' + name, read(MAIN / 'desktop/.local/stable-product-snapshot-54' / name))
for name in ['classes-54.log', 'classpath-54.log', 'classpath-54-initial-path.log']:
    put('root/' + name, read(REPO / 'desktop/.local/stable-build-repair' / name))
folder = HERE / 'physical-root-startup-54-attempt01'
for p in sorted(wide(folder).iterdir(), key=str):
    if p.is_file() and not p.name.startswith('private-'):
        register('root/physical-startup54/' + p.name, p.read_bytes())
for name in ['install-video-core-section54.py', 'freeze-video-core-section-native54.py',
    'jvm-method-name-audit-54.json', 'native54-root-observation.json']:
    put('root/' + name, read(HERE / name))

startup = json.loads(read(folder / 'result.json'))
assert startup['passed'] and startup['productionOverrides'] == 0 and startup['processExitCode'] == 0
assert not read(folder / 'stderr.raw.log')
assert json.loads(read(HERE / 'jvm-method-name-audit-54.json'))['issueCount'] == 0
observation = json.loads(read(HERE / 'native54-root-observation.json'))
report = dict(phase=54, sourceIdentityCount=1055, resourceRegistryCount=213, actualRuntimeEntries=101,
    wholeClassesPassed=True, wholeTestSourcesCompiled=True, actualProductStartupAndNormalClosePassed=True,
    unchangedProductMainStartup=startup, originalVideoLoadCoreSourceIntegrated=True,
    fullOriginalVideoPlayerSectionSourceIntegrated=True, fullOrdinaryVideoRootMountAccepted=False,
    newJvmDependencies=0, actual54RootVisualObservation=observation,
    windowsFullscreenGeometryScope='Full original Offline renderer on the actual54 graph, observed 150% display; see individual failed/accepted receipts',
    actualAccountAccepted=False, actualMainShellMediaAccepted=False, OSSMTCButtonAccepted=False,
    arbitraryNavigationPlacementRestoreAccepted=False, newExeDeployed=False,
    pending=['Full original Video VM and StateHolder', 'Same Repository raw cache/WBI/authorization bridge',
        'One playback authority transition and full original ordinary video Root mount',
        'Authenticated Main media/native/PiP route acceptance', 'Final portable EXE and updater validation'],
    sourceInventoryIsNotFunctionalOrReusePercentage=True)
put('integration-report.json', (json.dumps(report, indent=2) + '\n').encode())
raw = (json.dumps(dict(artifacts=rows, excludedRebuildableOutputs=excluded), indent=2) + '\n').encode()
wide(OUT / 'artifact-manifest.json').write_bytes(raw)
wide(OUT / 'README.md').write_text('''Original Video load core and full PlayerSection source integration
=================================================================

Stable v0.2.3 stays pinned at 3d5d19a. The original load/session/coordinator/use-case/supplement source bodies and full original VideoPlayerSection/Contracts renderer are installed through their sole pinned producers. DIRECT originals are copied by the existing sync once; selected outputs preserve original algorithms with explicit required Windows platform ports. The registry gains27 original identities and remains1055 sources/213 resources. No additional JVM dependency is introduced.

This is source integration, not a claim that the full ordinary video page is mounted. The full VideoPlaybackViewModel and VideoDetailScreenStateHolder, same Repository raw/WBI/authorization adapter, queue/plugins handoff and one-controller transition still need completion. Existing native ownership/event/settings adapters refer to the same accepted source and entry. No second HTTP client, player, cache or account is constructed by this source milestone.

Whole classes and test sources54 compile, the frozen101-entry graph has zero illegal JVM method names, and actual unchanged Main starts and closes normally with empty stderr. Fullscreen entry and exit still use the same Main Compose placement authority. A revision-bound EDT refresh performs real bounds changes on the existing peer, preserving current user bounds and device/monitor/transform checks. The exact bundled JDK Java/native source route to cached insets is preserved as source evidence; individual closed native runs and Root observations determine physical acceptance, including failed input delivery attempts. They do not prove arbitrary navigation, externalF11, another display, authenticated Main playback, OS-generated media events or a newly deployed EXE.

Frozen prepared packets, exact source deltas, actual graph/build/startup bytes and closed native runs are verified before copying. Rebuildable binaries, private fixture data, media and rendered images stay excluded. Source identity counts do not express functional parity or code reuse percentage.
''', encoding='utf-8', newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows), excludedBinaries=len(excluded), manifestSha256Bytes=sha(raw))))
