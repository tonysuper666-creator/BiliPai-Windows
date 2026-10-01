"""Fixture-only integration on caller-pinned immutable Main04; no product source overrides."""
from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, zipfile
sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
HERE = Path(__file__).resolve().parent
REPO = next(p for p in HERE.parents if (p / '.git').exists())
def safe(path):
    value = str(Path(path).resolve())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)
def sha(path): return hashlib.sha256(safe(path).read_bytes()).hexdigest()
def write(path, text):
    assert Path(path).resolve().is_relative_to(HERE)
    safe(path.parent).mkdir(parents=True, exist_ok=True)
    safe(path).write_text(text, encoding='utf-8', newline='\n')
def save(path, data): write(path, json.dumps(data, ensure_ascii=False, indent=2) + '\n')
def argsfile(path, values): write(path, '\n'.join('"' + str(value).replace('\\', '/') + '"' for value in values) + '\n')
def environment(root):
    env = os.environ.copy(); env['PYTHONDONTWRITEBYTECODE'] = '1'
    for name in ['APPDATA', 'LOCALAPPDATA', 'USERPROFILE', 'TEMP', 'TMP']:
        directory = root / name.lower(); directory.mkdir(parents=True, exist_ok=True); env[name] = str(directory)
    return env
phase, attempt, snapshot, expected_manifest, expected_cp = sys.argv[1:6]
assert attempt.isalnum()
snapshot = Path(snapshot).resolve()
assert sha(snapshot / 'manifest.json') == expected_manifest
assert sha(snapshot / 'ordered-runtime-cp.json') == expected_cp
cp = json.loads(safe(snapshot / 'ordered-runtime-cp.json').read_text(encoding='utf-8'))
assert len(cp) == 92
for row in cp: assert sha(row['path']) == row['sha256Bytes'], row['path']
main = next(row['path'] for row in cp if row.get('source') == 'desktop/build/classes/kotlin/main')
out = HERE / 'runs' / attempt
if phase == 'compile':
    assert not out.exists(); out.mkdir(parents=True)
    fixture = HERE / 'IntegrationFixture.kt'; assert fixture.is_file()
    frozen = out / 'frozen-sources/IntegrationFixture.kt'; write(frozen, fixture.read_text(encoding='utf-8'))
    spec = importlib.util.spec_from_file_location('main04_static_integration_compiler', REPO / 'desktop/.local/source9-appearance/compile-miuix.py')
    compiler = importlib.util.module_from_spec(spec); spec.loader.exec_module(compiler)
    jar = out / 'fixture-only.jar'
    values = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-cp', ';'.join(row['path'] for row in cp), '-Xfriend-paths=' + main,
              '-module-name', 'com_bilipai_desktop_bilipai_windows', '-d', jar, frozen]
    argsfile(out / 'compiler.args', values)
    compiler_home = out / 'compiler-home'; env = environment(compiler_home)
    process = subprocess.run([str(compiler.JAVA), '-Dfile.encoding=UTF-8', '-Duser.home=' + str(compiler_home), '-Djava.io.tmpdir=' + env['TEMP'], '-Xmx1g', '-cp', ';'.join(map(str, compiler.COMPILER)),
                             'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '@' + str(out / 'compiler.args')], cwd=HERE, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=80)
    write(out / 'compile.log', process.stdout + process.stderr); print(process.stdout + process.stderr)
    if process.returncode:
        save(out / 'compile-failure.json', dict(passed=False, returncode=process.returncode, fixtureOnly=True, logSha256Bytes=sha(out / 'compile.log')))
        sys.exit(process.returncode)
    existing = set()
    for row in cp:
        with zipfile.ZipFile(safe(row['path'])) as archive: existing.update(name for name in archive.namelist() if name.endswith('.class'))
    with zipfile.ZipFile(jar) as archive: classes = {name for name in archive.namelist() if name.endswith('.class')}
    assert not (classes & existing), classes & existing
    save(out / 'compile-evidence.json', dict(passed=True, snapshot=str(snapshot), actualMain04ManifestSha256Bytes=expected_manifest, orderedActual92CpSha256Bytes=expected_cp,
         orderedActual92Classpath=cp, fixtureJarSha256Bytes=sha(jar), compiledFixtureSha256Bytes=sha(frozen), fixtureClassCount=len(classes), productClassIntersection=[],
         existingMainProductOverrides=[], fixtureOnly=True, sharedGradle=False, HWND=False))
    print('PASS Main04 fixture-only compile; product class overlap0')
elif phase == 'run':
    evidence = json.loads((out / 'compile-evidence.json').read_text())
    assert sha(out / 'fixture-only.jar') == evidence['fixtureJarSha256Bytes']
    assert sha(out / 'frozen-sources/IntegrationFixture.kt') == evidence['compiledFixtureSha256Bytes']
    proof = out / 'proof'; assert not proof.exists(); proof.mkdir()
    from PIL import Image
    inputs = proof / 'inputs'; inputs.mkdir()
    alpha = Image.new('RGBA', (96, 64))
    for y in range(64):
        for x in range(96): alpha.putpixel((x, y), ((x * 7) % 256, (y * 11) % 256, ((x + y) * 3) % 256, (x * 13 + y * 5) % 256))
    alpha.save(inputs / 'alpha.png')
    rgb = Image.new('RGB', (96, 64))
    for y in range(64):
        for x in range(96): rgb.putpixel((x, y), (220 if x < 48 else 20, 180 if y < 32 else 30, 170 if (x < 48) == (y < 32) else 40))
    rgb.save(inputs / 'rgb.png')
    frames = [Image.new('RGB', (20, 14), color) for color in [(255, 0, 0), (0, 255, 0), (0, 0, 255)]]
    frames[0].save(inputs / 'animated.gif', save_all=True, append_images=frames[1:], duration=100, loop=0)
    frames[0].save(inputs / 'animated.webp', save_all=True, append_images=frames[1:], duration=100, loop=0, lossless=True)
    motion_input = REPO / 'desktop/.local/dynamic-gallery-motion-photo-parity/runs/04/media/source.mp4'
    assert sha(motion_input) == '4aebf236a1c56fc00d2d129c83c96b4f51c7dceb80784f1acd29809d1af08c7f'
    (inputs / 'motion.mp4').write_bytes(safe(motion_input).read_bytes())
    write(inputs / 'decode-failure.png', 'declared task malformed static image')
    task_home = out / 'task-home'; env = environment(task_home)
    java = REPO.parent / 'toolchain/jdk/jdk-21.0.12.1+1/bin/java.exe'
    values = ['-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '-Duser.home=' + str(task_home), '-Djava.io.tmpdir=' + env['TEMP'],
              '-cp', ';'.join([str(out / 'fixture-only.jar')] + [row['path'] for row in cp]), 'com.bilipai.desktop.ui.main04proof.IntegrationFixtureKt', proof, main]
    argsfile(out / 'run.args', values)
    process = subprocess.run([str(java), '@' + str(out / 'run.args')], cwd=HERE, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=75)
    write(out / 'run.log', process.stdout + process.stderr); print(process.stdout + process.stderr)
    save(out / 'runtime-process.json', dict(returncode=process.returncode, fixtureJarSha256Bytes=sha(out / 'fixture-only.jar'), runLogSha256Bytes=sha(out / 'run.log')))
    process.check_returncode()
    result = json.loads((proof / 'result.json').read_text()); assert result['passed'] and result['cases'] == 7
    independent = []
    def verify(value, label, details=None):
        independent.append(dict(passed=bool(value), label=label, details=details)); assert value, label
    reference_jpeg = proof / 'reference-quality95.jpg'; rgb.save(reference_jpeg, quality=95)
    for row in result['independentOutputs']:
        path = proof / row['path']; kind = row['kind']
        if kind in ('png', 'fallback-png'):
            with Image.open(path) as image:
                verify(image.format == 'PNG' and image.size == alpha.size, kind + ' actual complete PNG dimensions')
                before, after = list(alpha.get_flattened_data()), list(image.convert('RGBA').get_flattened_data())
                verify(all(left[3] == right[3] for left, right in zip(before, after)), kind + ' alpha channel exact')
                visible_error = max(abs(round(left[channel] * left[3] / 255) - round(right[channel] * right[3] / 255)) for left, right in zip(before, after) for channel in range(3))
                verify(visible_error <= 1, kind + ' visible premultiplied color preserved', {'maxError': visible_error})
        elif kind == 'jpg':
            with Image.open(path) as image, Image.open(reference_jpeg) as reference:
                verify(image.format == 'JPEG' and image.size == rgb.size, 'remaining input actual JPEG dimensions')
                verify(image.quantization == reference.quantization, 'actual JPEG independently quality95 quantization')
        elif kind in ('gif', 'webp'):
            verify(path.read_bytes() == (inputs / ('animated.' + kind)).read_bytes(), kind + ' exact original raw bytes')
            with Image.open(path) as image: verify(image.n_frames == 3, kind + ' retains three real animation frames')
        elif kind == 'motion':
            import xml.etree.ElementTree as ET
            data = path.read_bytes(); video_data = (inputs / 'motion.mp4').read_bytes()
            verify(data[-len(video_data):] == video_data, 'motion fallback exact MP4 tail')
            start = data.index(b'<x:xmpmeta'); end = data.index(b'</x:xmpmeta>') + len(b'</x:xmpmeta>')
            xml = ET.fromstring(data[start:end])
            desc = list(xml.iter('{http://www.w3.org/1999/02/22-rdf-syntax-ns#}Description'))[0]
            verify(desc.attrib['{http://ns.google.com/photos/1.0/camera/}MicroVideoOffset'] == str(len(video_data)), 'motion fallback valid original XMP video offset')
            with Image.open(path) as image: verify(image.format == 'JPEG' and image.size == alpha.size, 'motion fallback actual JPEG readable')
    save(out / 'independent-readback.json', dict(passed=True, assertions=len(independent), checks=independent, AndroidBitmapExecuted=False))
    for row in cp: assert sha(row['path']) == row['sha256Bytes']
    save(out / 'accepted-evidence.json', {**result, 'actualMain04ManifestSha256Bytes': expected_manifest, 'orderedActual92CpSha256Bytes': expected_cp,
         'actualRuntimeEntries': 92, 'fixtureJarSha256Bytes': sha(out / 'fixture-only.jar'), 'fixtureOnly': True, 'existingMainProductOverrides': [],
         'old135MatrixRerun': False, 'taskPrivateHome': str(task_home), 'allWritesWithinNewTaskLane': True,
         'independentReadbackAssertions': len(independent), 'motionSyntheticInputSha256Bytes': sha(inputs / 'motion.mp4'), 'AndroidBitmapExecuted': False})
    print('PASS seven requested new Main04 integration contracts; product overrides0')
else:
    raise ValueError(phase)
