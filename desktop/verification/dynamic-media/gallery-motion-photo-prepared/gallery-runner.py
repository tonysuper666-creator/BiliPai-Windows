"""Task-only original gallery/Motion Photo closure proof; no Gradle or Main writes."""
from pathlib import Path
import hashlib, importlib.util, json, os, struct, subprocess, sys, tempfile, zipfile
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
SNAP = REPO / 'desktop/.local/dynamic-resource-navigation/main-product-snapshot-01'
def ext(path):
    value = str(Path(path).resolve())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)
def sha(path): return hashlib.sha256(ext(path).read_bytes()).hexdigest()
def write(path, value): path.parent.mkdir(parents=True, exist_ok=True); path.write_text(value, encoding='utf-8', newline='\n')
def save(path, value): write(path, json.dumps(value, ensure_ascii=False, indent=2) + '\n')
def argsfile(path, values): write(path, '\n'.join('"' + str(v).replace('\\', '/') + '"' for v in values) + '\n')

phase = sys.argv[1]
attempt = sys.argv[2] if len(sys.argv) > 2 else '01'
assert attempt.isalnum()
out = HERE / 'gallery-runs' / attempt
compiled_out = HERE / 'gallery-runs' / (sys.argv[3] if len(sys.argv) > 3 else attempt)
cpfile = SNAP / 'ordered-runtime-cp.json'; manifestfile = SNAP / 'manifest.json'
assert sha(manifestfile) == '4780b40c15bd0de96f01fe5f60086bd2e67684764d075d8af8f437a27b0887c5'
assert sha(cpfile) == 'c2f31a97c207af9d501d669897deebfee51f4edf9a12b1280df59b542ec4b6ab'
cp = json.loads(cpfile.read_text())
assert len(cp) == 89
for row in cp: assert sha(Path(row['path'])) == row['sha256Bytes'], row['path']
spec = importlib.util.spec_from_file_location('owned_gallery_compiler', REPO / 'desktop/.local/source9-appearance/compile-miuix.py')
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
sources = [HERE / 'generated-acceptance-final/com/android/purebilibili/core/util/DesktopOriginalGalleryResultPolicy.kt', HERE / 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicGallerySelection.kt', HERE / 'GalleryFixture.kt']
assert len(sources) == 3
if phase == 'compile':
    assert not out.exists(); out.mkdir(parents=True)
    for source in sources: write(out / 'frozen-sources' / source.name, source.read_text(encoding='utf-8'))
    source_pins = [{'path': str(p.relative_to(HERE)), 'sha256Bytes': sha(p)} for p in sources]
    candidate = out / 'candidate-and-fixture.jar'
    main_kotlin = next(row['path'] for row in cp if row.get('source') == 'desktop/build/classes/kotlin/main')
    values = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-cp', ';'.join(row['path'] for row in cp),
        '-Xfriend-paths=' + main_kotlin, '-module-name', 'dynamic_gallery_motion_photo_parity', '-d', candidate] + sources
    argsfile(out / 'compiler.args', values)
    r = subprocess.run([str(c.JAVA), '-Dfile.encoding=UTF-8', '-Xmx2g', '-cp', ';'.join(map(str, c.COMPILER)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '@' + str(out / 'compiler.args')], capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=90)
    write(out / 'compile.log', r.stdout + r.stderr); print(r.stdout + r.stderr); r.check_returncode()
    product_entries = set()
    for row in cp:
        with zipfile.ZipFile(ext(row['path'])) as jar: product_entries.update(jar.namelist())
    with zipfile.ZipFile(candidate) as jar:
        classes = [name for name in jar.namelist() if name.endswith('.class')]
        assert not (set(classes) & product_entries), 'Unexpected production override'
    save(out / 'compile-evidence.json', {'passed': True, 'productionOverrides': 0, 'candidateNewClasses': len(classes),
        'historicalActualMainManifestSha256Bytes': sha(manifestfile), 'actualOrdered89CpSha256Bytes': sha(cpfile),
        'orderedDependencies': cp, 'sources': source_pins, 'candidateJarSha256Bytes': sha(candidate),
        'newMainIntegration': False, 'sharedGradle': False, 'EXIFWriterImplemented': False})
    print('PASS task-only candidate compile; zero existing Main/dependency class overlap')
elif phase == 'run':
    compiled = json.loads((compiled_out / 'compile-evidence.json').read_text())
    assert sha(compiled_out / 'candidate-and-fixture.jar') == compiled['candidateJarSha256Bytes']
    for row in compiled['sources']: assert sha(HERE / row['path']) == row['sha256Bytes']
    proof = out / 'proof'; assert not proof.exists(); proof.mkdir()
    media = HERE / 'runs/04/media/source.png'
    values = ['-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '-cp', ';'.join([str(compiled_out / 'candidate-and-fixture.jar')] + [row['path'] for row in cp]),
        'com.bilipai.desktop.ui.gallerymotionphotoproof.GalleryFixtureKt', proof, media]
    argsfile(out / 'run.args', values)
    r = subprocess.run([str(c.JAVA), '@' + str(out / 'run.args')], cwd=REPO, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)
    write(out / 'run.log', r.stdout + r.stderr); print(r.stdout + r.stderr); r.check_returncode()
    result = json.loads((proof / 'result.json').read_text()); assert result['passed']
    for row in cp: assert sha(row['path']) == row['sha256Bytes']
    save(out / 'accepted-evidence.json', {**result, 'historicalActualMainManifestSha256Bytes': sha(manifestfile), 'orderedRuntime89CpSha256Bytes': sha(cpfile),
        'candidateJarSha256Bytes': sha(compiled_out / 'candidate-and-fixture.jar'), 'productionOverrides': 0,
        'fixtureMediaSha256Bytes': sha(media), 'newMainIntegration': False, 'newAccountApi': False})
else: raise ValueError(phase)
