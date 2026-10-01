"""One new concern, pinned actual828 owner + exact frozen114 Assets binding.
Only selected MotionPhotoFiles family is overridden in the candidate run.
"""
from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, zipfile
sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
HERE = Path(__file__).resolve().parent
ROOT = next(p for p in HERE.parents if (p / '.git').exists())
PEER = ROOT / 'desktop/.local/dynamic-motion-photo-download-owner-parity'
GALLERY = ROOT / 'desktop/.local/dynamic-gallery-motion-photo-parity'
SNAP = ROOT / 'desktop/.local/dynamic-detail-reply-main-integration/main-product-snapshot-01'
def safe(p):
    s = str(Path(p).absolute())
    return Path(s if s.startswith('\\\\?\\') else '\\\\?\\' + s)
def sha(p): return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p, text):
    safe(p.parent).mkdir(parents=True, exist_ok=True)
    safe(p).write_text(text, encoding='utf-8', newline='\n')
def save(p, value): write(p, json.dumps(value, ensure_ascii=False, indent=2) + '\n')
def argsfile(p, values): write(p, '\n'.join('"' + str(v).replace('\\', '/') + '"' for v in values) + '\n')

assert sha(PEER / 'frozen-handoff.json') == 'bd97df3e2aa1ea4529fa49f9196b8f95db7e0100dfcc20aff79d0b091a14a503'
peer_freeze = json.loads(safe(PEER / 'frozen-handoff.json').read_text(encoding='utf-8'))
for row in peer_freeze['files']: assert sha(PEER / row['path']) == row['sha256Bytes']
assert sha(SNAP / 'manifest.json') == '8286563186e5d5be2495a598e1e32fb15091b7a459a5bbbc3d7e65243557e290'
assert sha(SNAP / 'ordered-runtime-cp.json') == '06316248361f2fae9d8eb53976ba1dc6936c5bd6416f98d2b6026c207ef9a217'
accepted = json.loads(safe(PEER / 'runs/05/accepted-evidence.json').read_text(encoding='utf-8'))
base = json.loads(safe(PEER / 'runs/05/compile-evidence.json').read_text(encoding='utf-8'))
cp = base['ordered92Classpath']; assert len(cp) == 92 and accepted['assertions'] == 51
for row in cp: assert sha(row['path']) == row['sha256Bytes']
main = next(row['path'] for row in cp if row.get('source') == 'desktop/build/classes/kotlin/main')
source_jar = PEER / 'runs/05/candidate-and-fixture.jar'
assert sha(source_jar) == accepted['candidateJarSha256Bytes'] == '06980001b41e25dcdc1a0346e0efda3c210909535c4fcd6536552d1760c3c930'
retained = HERE / 'dependencies/frozen-assets114.jar'
if not safe(retained).exists():
    safe(retained.parent).mkdir(parents=True, exist_ok=True); safe(retained).write_bytes(safe(source_jar).read_bytes())
assert sha(retained) == sha(source_jar)
spec = importlib.util.spec_from_file_location('motion_commit_compiler', ROOT / 'desktop/.local/source9-appearance/compile-miuix.py')
compiler = importlib.util.module_from_spec(spec); spec.loader.exec_module(compiler)
tools = [dict(path=str(p), sha256Bytes=sha(p)) for p in [compiler.JAVA] + compiler.COMPILER]
selected = HERE / 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicMotionPhotoFiles.kt'
fixture = HERE / 'CommitCancelFixture.kt'
phase = sys.argv[1]

if phase == 'compile':
    out = HERE / 'compile-01'; assert not safe(out).exists()
    sources = []
    for p in [selected, fixture]:
        target = out / 'sources' / p.name; write(target, safe(p).read_text(encoding='utf-8')); sources.append(target)
    jar = out / 'candidate-and-focused-fixture.jar'
    values = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-cp', ';'.join([str(retained)] + [row['path'] for row in cp]),
        '-Xfriend-paths=' + main + ',' + str(retained), '-module-name', 'motion_photo_commit_cancel_delta', '-d', jar] + sources
    argsfile(out / 'compiler.args', values)
    result = subprocess.run([str(compiler.JAVA), '-Dfile.encoding=UTF-8', '-Xmx2g', '-cp', ';'.join(map(str, compiler.COMPILER)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '@' + str(out / 'compiler.args')], capture_output=True, text=True,
        encoding='utf-8', errors='replace', timeout=90)
    write(out / 'compile.log', result.stdout + result.stderr); print(result.stdout + result.stderr); result.check_returncode()
    existing = set()
    for p in [retained] + [Path(row['path']) for row in cp]:
        with zipfile.ZipFile(safe(p)) as z: existing.update(z.namelist())
    with zipfile.ZipFile(safe(jar)) as z: classes = {n for n in z.namelist() if n.endswith('.class')}
    overlap = sorted(classes & existing)
    allowed = lambda n: n.startswith('com/bilipai/desktop/ui/DesktopDynamicMotionPhotoFiles$') or n in ['com/bilipai/desktop/ui/DesktopDynamicMotionPhotoFiles.class', 'com/bilipai/desktop/ui/DesktopDynamicMotionPhotoFilesKt.class']
    assert overlap and all(allowed(n) for n in overlap), overlap
    assert not any('DesktopDynamicImageAssets' in n or 'DesktopRepository' in n or 'DesktopSessionStore' in n or 'DesktopDynamicCardOperations' in n for n in classes)
    save(out / 'compile-evidence.json', dict(passed=True, sourceCount=2, sources=[dict(path=str(p.relative_to(HERE)), sha256Bytes=sha(p)) for p in sources],
        selectedSourceSha256Bytes=sha(selected), fixtureSourceSha256Bytes=sha(fixture), candidateJarSha256Bytes=sha(jar),
        actualMainSnapshotSha256Bytes=sha(SNAP / 'manifest.json'), actualMainOriginalRuntimeCpCount=89, runtimeDependencyCount=92,
        ordered92Classpath=cp, retainedFrozenAssets114Jar=dict(path=str(retained), sha256Bytes=sha(retained)),
        declaredFilesOnlyOverrideClasses=overlap, actualMainStoreRepoOpsSaveTargetOverride=False, AssetsBodyRecompiledOrChanged=False,
        PackingOrExifBodyChanged=False, tools=tools, HTTP=False, HWND=False, SharedGradle=False))
    print('PASS only selected Files + focused fixture compiled; actual owner classes not overridden')
elif phase in ['baseline', 'candidate']:
    out = HERE / (phase + '-proof-01'); assert not safe(out).exists(); safe(out).mkdir(parents=True)
    compiled = json.loads(safe(HERE / 'compile-01/compile-evidence.json').read_text(encoding='utf-8'))
    candidate = HERE / 'compile-01/candidate-and-focused-fixture.jar'; assert sha(candidate) == compiled['candidateJarSha256Bytes']
    for row in compiled['sources']: assert sha(HERE / row['path']) == row['sha256Bytes']
    for row in tools: assert sha(row['path']) == row['sha256Bytes']
    media = [GALLERY / 'runs/04/media/source.png', GALLERY / 'runs/04/media/source.mp4']
    pins = {row['path']: row['sha256Bytes'] for row in accepted['syntheticMediaPins']}
    for p in media: assert sha(p) == pins[p.relative_to(GALLERY).as_posix()]
    home = out / 'private-native-home'; env = os.environ.copy()
    for name in ['APPDATA', 'LOCALAPPDATA', 'USERPROFILE', 'TEMP', 'TMP']:
        target = home / name.lower(); safe(target).mkdir(parents=True); env[name] = str(target)
    ordered = [candidate, retained] if phase == 'candidate' else [retained, candidate]
    files_source = candidate if phase == 'candidate' else retained
    values = ['-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '-Duser.home=' + str(home), '-Djava.io.tmpdir=' + str(home / 'temp'),
        '-cp', ';'.join(map(str, ordered)) + ';' + ';'.join(row['path'] for row in cp),
        'com.bilipai.desktop.ui.commitcancelproof.CommitCancelFixtureKt', out / 'files', *media, main, retained, files_source, phase]
    argsfile(out / 'run.args', values)
    result = subprocess.run([str(compiler.JAVA), '@' + str(out / 'run.args')], cwd=ROOT, env=env, capture_output=True, text=True,
        encoding='utf-8', errors='replace', timeout=45)
    write(out / 'run.log', result.stdout + result.stderr); print(result.stdout + result.stderr); result.check_returncode()
    result_json = json.loads(safe(out / 'files/result.json').read_text(encoding='utf-8')); assert result_json['passed']
    assert result_json['correctCancellationTargetPreserved'] == (phase == 'candidate')
    jars = [retained, candidate] + [Path(row['path']) for row in cp]
    for loaded in result_json['loadedClasses']:
        location = Path(loaded['codeSource']); assert location in jars
        with zipfile.ZipFile(safe(location)) as z:
            data = z.read(loaded['className'].replace('.', '/') + '.class')
            assert hashlib.sha256(data).hexdigest() == loaded['classSha256Bytes']
    for row in cp: assert sha(row['path']) == row['sha256Bytes']
    for row in peer_freeze['files']: assert sha(PEER / row['path']) == row['sha256Bytes']
    save(out / 'accepted-evidence.json', dict(**result_json, compileEvidenceSha256Bytes=sha(HERE / 'compile-01/compile-evidence.json'),
        resultSha256Bytes=sha(out / 'files/result.json'), sourceDeltaAuditSha256Bytes=sha(HERE / 'source-delta-audit.json'),
        actualMainSnapshotSha256Bytes=sha(SNAP / 'manifest.json'), originalRuntimeCpCount=89, unchangedAssets114JarSha256Bytes=sha(retained),
        candidateJarSha256Bytes=sha(candidate), allRuntimePinsPrePostVerified=True, loadedClassBytesVerified=True,
        old114ArtifactsUnchanged=True, old51CasesRerun=False, fixturePurpose='only final Store-monitor wait save-Job cancellation',
        actualMainStoreRepoOpsSaveTargetOverride=False, FilesFamilyOverride=phase == 'candidate'))
    print('accepted SHA ' + sha(out / 'accepted-evidence.json'))
else: raise ValueError(phase)
