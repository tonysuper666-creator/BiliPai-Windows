"""Task-only Assets family candidate on immutable actual Main03; four narrow batch contracts."""
from pathlib import Path
import difflib, hashlib, importlib.util, json, os, subprocess, sys, zipfile
sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
HERE = Path(__file__).resolve().parent
REPO = next(p for p in HERE.parents if (p / '.git').exists())
SNAP = REPO / 'desktop/.local/dynamic-media-main-integration/main-product-snapshot-03'
SOURCE = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt'
EXPECTED_MANIFEST = 'f9d91763db17acaa59753f5dccad4feb88512c8ad2984062520ed867943df6a6'
EXPECTED_CP = '3abb7e2fecccb5bd0693e6b0868d002b576fc8f689d734d86bd5737fe1c39cac'
EXPECTED_SOURCE = '5ed7475e399b768f1090e3f4a22d15f26c45c6debdb336422a0bfb774f02c868'
def safe(path):
    value = str(Path(path).resolve())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)
def sha(path): return hashlib.sha256(safe(path).read_bytes()).hexdigest()
def write(path, text):
    assert Path(path).resolve().is_relative_to(HERE)
    safe(path.parent).mkdir(parents=True, exist_ok=True)
    safe(path).write_text(text, encoding='utf-8', newline='\n')
def save(path, data): write(path, json.dumps(data, ensure_ascii=False, indent=2) + '\n')
def argsfile(path, values): write(path, '\n'.join('"' + str(v).replace('\\', '/') + '"' for v in values) + '\n')
def environment(root):
    env = os.environ.copy(); env['PYTHONDONTWRITEBYTECODE'] = '1'
    for name in ['APPDATA', 'LOCALAPPDATA', 'USERPROFILE', 'TEMP', 'TMP']:
        directory = root / name.lower(); directory.mkdir(parents=True, exist_ok=True); env[name] = str(directory)
    return env
assert sha(SNAP / 'manifest.json') == EXPECTED_MANIFEST
assert sha(SNAP / 'ordered-runtime-cp.json') == EXPECTED_CP
cp = json.loads(safe(SNAP / 'ordered-runtime-cp.json').read_text())
assert len(cp) == 92
for row in cp: assert sha(row['path']) == row['sha256Bytes'], row['path']
main = next(row['path'] for row in cp if row.get('source') == 'desktop/build/classes/kotlin/main')
phase = sys.argv[1]
candidate = HERE / 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt'
if phase == 'prepare':
    assert not candidate.exists()
    assert sha(SOURCE) == EXPECTED_SOURCE
    original = SOURCE.read_text(encoding='utf-8')
    before = '''        for ((index, raw) in rawUrls.withIndex()) {
            val url = normalizeImageUrl(raw)
            val target = parent.resolve("BiliPai-$stamp-${index + 1}.${extension(resolveImageShareMimeType(url))}")
            write(url, DesktopDynamicSaveTarget(target, false), MAX_IMAGE_BYTES)
        }
        true
'''
    after = '''        var allSaved = true
        for ((index, raw) in rawUrls.withIndex()) {
            checkpoint()
            val saved = try {
                val url = normalizeImageUrl(raw)
                val target = parent.resolve("BiliPai-$stamp-${index + 1}.${extension(resolveImageShareMimeType(url))}")
                write(url, DesktopDynamicSaveTarget(target, false), MAX_IMAGE_BYTES)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                checkpoint()
                false
            }
            if (!saved) allSaved = false
        }
        allSaved
'''
    assert original.count(before) == 1
    prepared = original.replace(before, after, 1)
    write(candidate, prepared)
    write(HERE / 'original/DesktopDynamicImageAssets.kt', original)
    write(HERE / 'candidate.diff', ''.join(difflib.unified_diff(original.splitlines(True), prepared.splitlines(True), fromfile='actual-Main03-Assets', tofile='batch-candidate-Assets')))
    upstream_path = 'app/src/main/java/com/android/purebilibili/feature/dynamic/components/ImagePreviewDialog.kt'
    upstream = subprocess.run(['git', 'show', 'v0.2.3-alpha.9:' + upstream_path], cwd=REPO, capture_output=True, check=True).stdout
    lines = upstream.decode('utf-8').splitlines()
    write(HERE / 'original/alpha9-save-all-contract.txt', '\n'.join(f'{i + 1}: {line}' for i, line in enumerate(lines) if 504 <= i + 1 <= 518) + '\n')
    selectors = original[original.index('internal data class DesktopDynamicSaveTarget'):]
    assert prepared[prepared.index('internal data class DesktopDynamicSaveTarget'):] == selectors
    save(HERE / 'source-contract.json', dict(actualMain03ManifestSha256Bytes=EXPECTED_MANIFEST, orderedActual92CpSha256Bytes=EXPECTED_CP, mainAssetsSha256Bytes=EXPECTED_SOURCE,
         upstreamRef='v0.2.3-alpha.9', upstreamPath=upstream_path, upstreamBlobSha256Bytes=hashlib.sha256(upstream).hexdigest(), upstreamSaveAllLine=511,
         contract='Each normalized URL is attempted sequentially; ordinary item failures contribute false while later items still run; cancellation propagates immediately.',
         permittedDelta='Only existing saveImages loop aggregation and per-item exception handling', candidateSha256Bytes=sha(candidate), rootParentPickerAdapterBodyUnchanged=True,
         allWritesWithinNewTaskLane=True, MainOrOtherLaneOrGradleWrites=False, originalEvidence114Or135ReusedAsAcceptance=False))
    print('PREPARED existing saveImages delta only; Main03 parent picker adapter unchanged')
    sys.exit(0)
attempt = sys.argv[2]
assert attempt.isalnum()
out = HERE / 'runs' / attempt
if phase == 'compile':
    assert not out.exists(); out.mkdir(parents=True)
    contract = json.loads((HERE / 'source-contract.json').read_text())
    assert sha(candidate) == contract['candidateSha256Bytes']
    prepared = candidate.read_text(encoding='utf-8')
    omit = 'internal data class DesktopDynamicSaveTarget(val path: Path, val replaceExisting: Boolean)'
    assert prepared.count(omit) == 1
    compile_asset = out / 'src/DesktopDynamicImageAssets.kt'
    write(compile_asset, prepared.replace(omit, '// Actual immutable Main03 DesktopDynamicSaveTarget retained; no class override.'))
    sources = [compile_asset, HERE / 'BatchFixture.kt']
    for source in sources: write(out / 'frozen-sources' / source.name, source.read_text(encoding='utf-8'))
    spec = importlib.util.spec_from_file_location('task_batch_compiler', REPO / 'desktop/.local/source9-appearance/compile-miuix.py')
    compiler = importlib.util.module_from_spec(spec); spec.loader.exec_module(compiler)
    jar = out / 'candidate-and-fixture.jar'
    values = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-cp', ';'.join(row['path'] for row in cp), '-Xfriend-paths=' + main,
              '-module-name', 'com_bilipai_desktop_bilipai_windows', '-d', jar] + sources
    argsfile(out / 'compiler.args', values)
    compiler_home = out / 'compiler-home'
    env = environment(compiler_home)
    process = subprocess.run([str(compiler.JAVA), '-Dfile.encoding=UTF-8', '-Duser.home=' + str(compiler_home), '-Djava.io.tmpdir=' + env['TEMP'], '-Xmx1g', '-cp', ';'.join(map(str, compiler.COMPILER)),
                             'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '@' + str(out / 'compiler.args')], cwd=HERE, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=80)
    write(out / 'compile.log', process.stdout + process.stderr)
    print(process.stdout + process.stderr)
    if process.returncode:
        save(out / 'compile-failure.json', dict(passed=False, returncode=process.returncode, compilerOnly=True, logSha256Bytes=sha(out / 'compile.log')))
        sys.exit(process.returncode)
    existing = set()
    for row in cp:
        with zipfile.ZipFile(safe(row['path'])) as archive: existing.update(archive.namelist())
    with zipfile.ZipFile(jar) as archive: classes = {name for name in archive.namelist() if name.endswith('.class')}
    overlap = classes & existing
    allowed = lambda name: name.startswith('com/bilipai/desktop/ui/DesktopDynamicImageAssets$') or name.startswith('com/bilipai/desktop/ui/DesktopDynamicImageAssetsKt$') or name in ['com/bilipai/desktop/ui/DesktopDynamicImageAssets.class', 'com/bilipai/desktop/ui/DesktopDynamicImageAssetsKt.class']
    assert all(allowed(name) for name in overlap), overlap
    assert 'com/bilipai/desktop/ui/DesktopDynamicSaveTarget.class' not in classes
    save(out / 'compile-evidence.json', dict(passed=True, actualMain03ManifestSha256Bytes=EXPECTED_MANIFEST, orderedActual92CpSha256Bytes=EXPECTED_CP,
         orderedActual92Classpath=cp, candidateJarSha256Bytes=sha(jar), candidateInstallSourceSha256Bytes=sha(candidate), declaredAssetsFamilyOnlyOverrideClassNames=sorted(overlap),
         StoreRepoOpsSaveTargetMotionFilesOverride=False, rootParentPickerAdapterBodyUnchanged=True, compileAdaptationOnly='Exclude unchanged SaveTarget declaration to retain actual Main03 class',
         sources=[dict(path=str(source), sha256Bytes=sha(source)) for source in sources], compilerEntrypoint='K2JVMCompiler 2.4.0', sharedGradle=False, HWND=False))
    print('PASS narrow candidate compile: Assets family only; 92 actual Main03 entries')
elif phase in ('run', 'accept'):
    evidence = json.loads((out / 'compile-evidence.json').read_text())
    assert sha(out / 'candidate-and-fixture.jar') == evidence['candidateJarSha256Bytes']
    assert sha(candidate) == evidence['candidateInstallSourceSha256Bytes']
    for row in evidence['sources']: assert sha(row['path']) == row['sha256Bytes']
    proof = out / 'proof'; task_home = out / 'task-home'
    if phase == 'run':
        assert not proof.exists(); proof.mkdir()
        env = environment(task_home)
        java = REPO.parent / 'toolchain/jdk/jdk-21.0.12.1+1/bin/java.exe'
        values = ['-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '-Duser.home=' + str(task_home), '-Djava.io.tmpdir=' + env['TEMP'],
                  '-cp', ';'.join([str(out / 'candidate-and-fixture.jar')] + [row['path'] for row in cp]), 'com.bilipai.desktop.ui.batchproof.BatchFixtureKt', proof, main, out / 'candidate-and-fixture.jar']
        argsfile(out / 'run.args', values)
        process = subprocess.run([str(java), '@' + str(out / 'run.args')], cwd=HERE, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=70)
        write(out / 'run.log', process.stdout + process.stderr); print(process.stdout + process.stderr); process.check_returncode()
    else:
        assert proof.is_dir() and (out / 'run.log').read_text().startswith('PASS 36 assertions / 4 batch contracts:')
    result = json.loads((proof / 'result.json').read_text())
    assert result['passed'] and result['cases'] == 4
    for row in cp: assert sha(row['path']) == row['sha256Bytes']
    save(out / 'accepted-evidence.json', dict(**result, actualMain03ManifestSha256Bytes=EXPECTED_MANIFEST, orderedActual92CpSha256Bytes=EXPECTED_CP,
         actualRuntimeEntries=92, candidateJarSha256Bytes=sha(out / 'candidate-and-fixture.jar'), candidateInstallSourceSha256Bytes=sha(candidate),
         declaredAssetsFamilyOnlyOverrideClassNames=evidence['declaredAssetsFamilyOnlyOverrideClassNames'], rootParentPickerAdapterBodyUnchanged=True,
         StoreRepoOpsSaveTargetMotionFilesOverride=False, originalEvidence114Or135ReusedAsAcceptance=False, taskPrivateHome=str(task_home), allWritesWithinNewTaskLane=True))
    print('PASS four batch contracts; actual Main03 owner classes; declared Assets family override only')
else:
    raise ValueError(phase)
