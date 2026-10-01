from pathlib import Path
import hashlib, json, sys

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
OUT = REPO / 'desktop/verification/stable-article-video-content-native-repair'
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
    if Path(name).suffix in ('.class', '.jar', '.kotlin_module', '.pyc', '.png', '.jpg', '.mp4'):
        excluded.append(dict(path=name, sha256Bytes=sha(data), reason='Rebuildable binary or private fixture media/render output'))
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

packet(MAIN / 'desktop/.local/stable-article-full-parity', 'prepared/article', 'frozen-handoff.json',
    '57c029afb0951a9fa23a7fbf3e2c968888e960186723f13ca9d5a30b1e805355', 'artifacts', 74)
packet(MAIN / 'desktop/.local/stable-video-player-page-parity', 'prepared/content', 'frozen-content.json',
    'bd8f8c1d9ed75154a4ac3ee2c593128be0f9849735beb859f7c5e721199d1484', 'rawArtifacts', 304)
packet(MAIN / 'desktop/.local/stable-content-dependency-license-parity', 'prepared/dependency-licenses', 'frozen-handoff.json',
    '29ac9b986320a46ad593a6d07a27c05103f9d98b9ca122a78f530f3eabff2b86', 'rawArtifacts', 33)
native50 = MAIN / 'desktop/.local/stable-offline-native-integration-proof'
for name, pin, count in [
    ('closed-actual50-03-normal', '08f858f6abd0800d2642eae71c513b99405c527e287c15f33171dd3cef18d911', 20),
    ('diagnostic50-windows', '4e621c078b4a6d31dbb8a574d0f5689ece6741a5ff0e22f1df843722801b5849', 18),
]:
    # The older packet may reference files outside its closed packet directory.
    folder = native50 / name
    raw = read(folder / 'frozen-handoff.json')
    assert sha(raw) == pin
    put('native/' + name + '/frozen-handoff.json', raw)
    items = json.loads(raw)['artifacts']
    assert len(items) == count
    for item in items:
        p = Path(item['path'])
        if not p.is_absolute():
            p = folder / p
        data = read(p)
        assert sha(data) == item['sha256Bytes']
        register('native/' + p.relative_to(native50).as_posix(), data)
native = MAIN / 'desktop/.local/stable-offline-native-integration-proof51'
for name, pin, count in [
    ('closed-actual52-01-normal', 'e59930d2346265cf58d21f6b84654687d357dd21b1594b0290993d064ba23b64', 23),
    ('closed-actual53-01-normal', sys.argv[1], int(sys.argv[2])),
]:
    folder = native / name
    raw = read(folder / 'frozen-handoff.json')
    assert sha(raw) == pin
    put('native/' + name + '/frozen-handoff.json', raw)
    items = json.loads(raw)['rawArtifacts']
    assert len(items) == count
    for item in items:
        p = Path(item['path'])
        if not p.is_absolute():
            p = folder / p
        data = read(p)
        assert sha(data) == item['sha256Bytes']
        register('native/' + p.relative_to(native).as_posix(), data)
for name in ['article-content-install51', 'content-licenses-install53']:
    for p in sorted(wide(HERE / name).rglob('*'), key=str):
        if p.is_file():
            register('root/' + name + '/' + p.relative_to(wide(HERE / name)).as_posix(), p.read_bytes())
for phase in [51, 52, 53]:
    for name in ['manifest.json', 'ordered-runtime-cp.json']:
        put(f'root/snapshot{phase}/' + name, read(MAIN / f'desktop/.local/stable-product-snapshot-{phase}' / name))
    put(f'root/jvm-method-name-audit-{phase}.json', read(HERE / f'jvm-method-name-audit-{phase}.json'))
    for name in [f'classes-{phase}.log', f'classpath-{phase}.log']:
        put('root/' + name, read(REPO / 'desktop/.local/stable-build-repair' / name))
for phase in [52, 53]:
    folder = HERE / f'physical-root-startup-{phase}-attempt01'
    for p in sorted(wide(folder).iterdir(), key=str):
        if p.is_file() and not p.name.startswith('private-'):
            register(f'root/physical-startup{phase}/' + p.name, p.read_bytes())
for name in ['install-article-content51.py', 'install-content-licenses53.py', 'freeze-article-content-native53.py',
    'run-physical-root-startup.py', 'PhysicalRootStartupFixture.java', 'native52-root-observation.json', 'native53-root-observation.json']:
    put('root/' + name, read(HERE / name))
for name in ['classes-51-initial.log', 'classes-51-owner-repair.log']:
    put('root/' + name, read(REPO / 'desktop/.local/stable-build-repair' / name))
for name in ['prepared-manifest.json', 'fixture-delta.json', 'prepare.py']:
    put('native/prepared-fixture/' + name, read(native / name))
startup = json.loads(read(HERE / 'physical-root-startup-53-attempt01/result.json'))
assert startup['passed'] and startup['productionOverrides'] == 0 and startup['processExitCode'] == 0
assert not read(HERE / 'physical-root-startup-53-attempt01/stderr.raw.log')
assert json.loads(read(HERE / 'jvm-method-name-audit-51.json'))['issueCount'] == 1
assert json.loads(read(HERE / 'jvm-method-name-audit-52.json'))['issueCount'] == 0
assert json.loads(read(HERE / 'jvm-method-name-audit-53.json'))['issueCount'] == 0
report = dict(phase=53, sourceIdentityCount=1028, resourceRegistryCount=213, actualRuntimeEntries=101,
    wholeClassesPassed=True, wholeTestSourcesCompiled=True, actualProductStartupAndNormalClosePassed=True,
    unchangedProductMainStartup=startup, fullOriginalArticleMounted=True,
    fullOriginalVideoContentSourceIntegrated=True, fullOrdinaryVideoPageMounted=False,
    newJvmDependencies=4, dependencyLicenseResources=5,
    composeInlineReturnFailureRetainedAndFixed=True, emptyHStringPublicationFailureRetainedAndFixed=True,
    windowsFullscreenGeometryScope='Full original Offline fixture, one observed 150% display; exact visual results are separate',
    normalWindowGeometryAfterFullscreenExitAccepted=True, reenteredFullscreenVisualAccepted=False,
    actual53RootVisualObservation=json.loads(read(HERE / 'native53-root-observation.json')),
    actualAccountAccepted=False, actualMainShellMediaAccepted=False, OSSMTCButtonAccepted=False, newExeDeployed=False,
    pending=['Full original Video VM, StateHolder and PlayerSection mount', 'Remaining full content leaves',
        'Authenticated Main media/native/PiP route acceptance', 'Final portable EXE and updater validation'],
    sourceInventoryIsNotFunctionalOrReusePercentage=True)
put('integration-report.json', (json.dumps(report, indent=2) + '\n').encode())
raw = (json.dumps(dict(artifacts=rows, excludedRebuildableOutputs=excluded), indent=2) + '\n').encode()
wide(OUT / 'artifact-manifest.json').write_bytes(raw)
wide(OUT / 'README.md').write_text('''Full original Article, Video content and Windows native repairs
================================================================

Stable v0.2.3 stays pinned at 3d5d19a. The full original ArticleDetailScreen is mounted through the actual typed NavDisplay entry, canonical nine-field article model and same repository/account epoch. Covered entries retain loading; removed entries cancel their own Job. Back and user navigation consume current foreground, and article return also checks the exact current key. Gallery saves reuse the same image actor and actual caller Job; text sharing borrows the Root native actor with the Article leaf scope and live owner. Composition disposal is not claimed as an atomic stack/file-publication gate.

Full original VideoContentSection, RelatedVideoItem, AI summary, supplement and rich-text notes are installed as the source foundation for the remaining ordinary-video mount. Four official JVM dependencies are resolved in the actual Gradle graph; five resource files preserve their original license texts and attribution/provenance limits. No synthetic note editor or default state is claimed as a mounted full video page.

Whole classes/test sources53 pass, and the frozen 101-entry graph has zero illegal JVM method names. The failed51 Compose inline-return method reference remains preserved, followed by its52 conditional-branch repair. Actual unchanged product Main starts and closes normally in isolated data; the actual53 startup stderr is empty. The52 real native actor's NPE on empty HSTRING publication is retained, followed by53 nullable-handle repair. The Microsoft HSTRING contract represents the empty string as a null handle: https://learn.microsoft.com/en-us/windows/win32/winrt/hstring .

Windows fullscreen exit uses the same Main Compose placement setter. Two real bounds changes on one EDT turn make the existing AWT peer reapply its DPI conversion, guarded by the same monitor/transform and request revision. Actual52 Root Sky inputs observe aligned normal geometry after two exits at150% scaling and successful next-source change; its existing SMTC failure stops later checks. Actual53 native, Root input and visual findings are preserved without extending them to authenticated Main Shell, OS-generated SMTC events or arbitrary routes. Consult integration-report and closed native receipts for exactly accepted and still-failed scope. No desktop EXE is replaced by this source milestone.

All raw prepared packets, install deltas, failed and corrected builds, immutable graph hashes and closed normal native observations are verified before copying. Rebuildable binaries, media and private fixture state/render outputs stay excluded with explicit hashes/reasons. Source counts do not express parity or reuse percentage.
''', encoding='utf-8', newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows), excludedBinaries=len(excluded), manifestSha256Bytes=sha(raw))))
