from pathlib import Path
import hashlib, json

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
OUT = REPO / 'desktop/verification/stable-full-controls-personal-window-readiness'
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
    assert not p.exists(), name
    p.write_bytes(data)
    rows.append(dict(path=name, sha256Bytes=sha(data), sizeBytes=len(data)))

def register(name, data):
    if Path(name).suffix in ('.class', '.jar', '.kotlin_module', '.pyc', '.png', '.jpg', '.mp4'):
        excluded.append(dict(path=name, sha256Bytes=sha(data), reason='Rebuildable binary or private fixture media/render output'))
    else:
        put(name, data)

for lane, prefix, filename, pin, key, count in [
    ('stable-personal-queue-start-parity', 'prepared/queue', 'frozen-handoff.json', '4db0037aa5066bf15bece6588681c91fdf66d9061b835f8585aadff19feed1f9', 'artifacts', 27),
    ('stable-personal-watchlater-parity', 'prepared/watchlater', 'frozen-handoff.json', '2d5d6ba3e5830dc07e9716b6b21833c07f4153608a4d0c34c55f1a1182e891ac', 'artifacts', 75),
    ('stable-personal-following-parity', 'prepared/following', 'frozen-handoff.json', 'e45fd84a73f7db257351b66e29e737c19badceeeb70d76f7cad52c8f164d2830', 'artifacts', 81),
    ('stable-video-player-full-controls-parity', 'prepared/controls', 'frozen-stage2.json', 'd40fd1b37f2c4c58af97ce0314e70f68f412b4d90d2ec17b7830d65d1dffcd84', 'rawArtifacts', 790),
]:
    folder = MAIN / 'desktop/.local' / lane
    raw = read(folder / filename)
    assert sha(raw) == pin
    put(prefix + '/' + filename, raw)
    artifacts = json.loads(raw)[key]
    assert len(artifacts) == count
    for item in artifacts:
        data = read(folder / item['path'])
        assert sha(data) == item['sha256Bytes'], item['path']
        register(prefix + '/' + item['path'], data)

native = MAIN / 'desktop/.local/stable-offline-native-integration-proof'
for directory, pin, count in [
    ('diagnostic14', '94c20fa25892c8d74214b5a5ee6b4df27b3826fd76040f0a53bf1e0177084f35', 17),
    ('closed-actual50-01-normal', None, 15),
    ('closed-actual50-02-normal', '5e664bb2d84171c92eb58b9062cf530db7fc8b90be00ffebe322b1d20907dd82', 20),
]:
    raw = read(native / directory / 'frozen-handoff.json')
    if pin is None:
        assert sha(raw).startswith('7b3ae497') and sha(raw).endswith('0f0a5b')
    else:
        assert sha(raw) == pin
    put('native/' + directory + '/frozen-handoff.json', raw)
    artifacts = json.loads(raw)['artifacts']
    assert len(artifacts) == count
    for item in artifacts:
        p = Path(item['path'])
        if not p.is_absolute():
            p = native / directory / p
        data = read(p)
        assert sha(data) == item['sha256Bytes'], str(p)
        register('native/' + p.relative_to(native).as_posix(), data)

for name in ['window-readiness50', 'personal-queue-install50', 'personal-watchlater-install50', 'player-controls-install50', 'player-controls-install50-attempt01', 'following-install50', 'following-install50-attempt01']:
    folder = HERE / name
    assert folder.is_dir()
    for p in sorted(wide(folder).rglob('*'), key=str):
        if p.is_file():
            register('root/' + name + '/' + p.relative_to(wide(folder)).as_posix(), p.read_bytes())
for p in sorted(wide(HERE / 'physical-root-startup-50-attempt01').iterdir(), key=str):
    if p.is_file() and not p.name.startswith('private-'):
        register('root/physical-startup50/' + p.name, p.read_bytes())
for name in ['install-personal-queues50.py', 'install-player-controls50.py', 'install-following50.py', 'watchlater-whitespace50.json', 'freeze-full-controls50.py', 'run-physical-root-startup.py', 'PhysicalRootStartupFixture.java', 'jvm-method-name-audit-50.json']:
    put('root/' + name, read(HERE / name))
for name in ['manifest.json', 'ordered-runtime-cp.json']:
    put('root/snapshot50/' + name, read(MAIN / 'desktop/.local/stable-product-snapshot-50' / name))
for name in ['classes-50.log', 'classpath-50.log']:
    put('root/' + name, read(REPO / 'desktop/.local/stable-build-repair' / name))
startup = json.loads(read(HERE / 'physical-root-startup-50-attempt01/result.json'))
assert startup['passed'] and startup['productionOverrides'] == 0 and startup['processExitCode'] == 0
assert json.loads(read(HERE / 'jvm-method-name-audit-50.json'))['issueCount'] == 0
report = dict(phase=50, wholeClassesPassed=True, wholeTestSourcesCompiled=True, sourceIdentityCount=1007,
    resourceCount=213, actualRuntimeEntries=97, newRuntimeDependencies=0,
    actualProductWindowStartupPassed=True, actualProductNormalShutdownPassed=True,
    unchangedProductMainStartup=startup, initialOfflineFullscreenAccepted=True,
    originalFullscreenToggleAccepted=True, originalNextEpisodeSourceChanged=True,
    fullOriginalWatchLaterMounted=True, fullOriginalFollowingMounted=True,
    completeOrdinaryControlsSourceFoundation=True, nativeWindowLayoutAccepted=False,
    nativeRootMainShellAccepted=False, actualAccountAccepted=False, PiPAccepted=False,
    OSSMTCButtonAccepted=False, newExeDeployed=False,
    nativeFailure='At 150% DPI the restored Main window is smaller than its original AWT anchor and shaped popup. Next click changed source; the fixture then read a disposed old popup peer and stopped. Separate later runs retain that fixture correction.',
    pending=['Windows fullscreen exit geometry', 'Full ordinary Video state holder/page mount',
        'Other original content leaves', 'Authenticated route/native/PiP acceptance', 'Final portable desktop EXE'])
put('integration-report.json', (json.dumps(report, indent=2) + '\n').encode())
raw = (json.dumps(dict(artifacts=rows, excludedRebuildableOutputs=excluded), indent=2) + '\n').encode()
wide(OUT / 'artifact-manifest.json').write_bytes(raw)
wide(OUT / 'README.md').write_text('''Full controls, personal pages and first fullscreen entry
======================================================

Stable v0.2.3 remains pinned at 3d5d19a. Complete original ordinary player controls are integrated as source foundations. Full original WatchLater and Following pages use the existing retained account, repository, cache and actions. History/List/Listen queue starts now retain the selected CID and resume position. The Windows control window reports readiness after Skiko componentShown listeners, then the same Main fullscreen setter replays the original pending request; no new fullscreen authority or original Offline business algorithm is introduced.

Whole classes50 and test-source compilation pass with 1007 source identities, 213 resources and the same 97 runtime entries. Actual unchanged product Main starts and closes normally in isolated application data, with zero product-class replacements. Source inventory is not a functional-parity or source-reuse percentage.

Separate immutable actual50 native tests establish first-entry fullscreen and original enter/exit controls. The original next action changes the selected task and native source. The normal-window layout fails visual verification at 150% DPI: its AWT children and shaped controls extend beyond the restored native Main client. One run times out before Root input; the second later reads the already-disposed popup peer after successful next-source change. These failures and diagnostic14 root-cause evidence remain intact. PiP, OS SMTC input, real accounts and complete Main Shell media routes are not accepted by this cohort. No desktop EXE was replaced.

Frozen source packets, exact install deltas, build receipts, immutable classpath pins, closed native attempts and normal startup evidence are bound by raw byte hashes. Rebuildable binaries, media and private fixture render/state outputs are excluded with explicit hashes/reasons.
''', encoding='utf-8', newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows), excludedBinaries=len(excluded), manifestSha256Bytes=sha(raw))))
