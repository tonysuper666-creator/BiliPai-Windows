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
out = HERE / 'runs' / attempt
compiled_out = HERE / 'runs' / (sys.argv[3] if len(sys.argv) > 3 else attempt)
cpfile = SNAP / 'ordered-runtime-cp.json'; manifestfile = SNAP / 'manifest.json'
assert sha(manifestfile) == '4780b40c15bd0de96f01fe5f60086bd2e67684764d075d8af8f437a27b0887c5'
assert sha(cpfile) == 'c2f31a97c207af9d501d669897deebfee51f4edf9a12b1280df59b542ec4b6ab'
cp = json.loads(cpfile.read_text())
assert len(cp) == 89
for row in cp: assert sha(Path(row['path'])) == row['sha256Bytes'], row['path']
spec = importlib.util.spec_from_file_location('owned_gallery_compiler', REPO / 'desktop/.local/source9-appearance/compile-miuix.py')
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
sources = sorted((HERE / 'generated-acceptance-final').rglob('*.kt')) + [HERE / 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicMotionPhotoFiles.kt', HERE / 'PackingFixtureAcceptance.kt']
assert len(sources) == 4
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
    from PIL import Image
    out.mkdir(parents=True, exist_ok=True)
    compiled = json.loads((compiled_out / 'compile-evidence.json').read_text())
    assert sha(compiled_out / 'candidate-and-fixture.jar') == compiled['candidateJarSha256Bytes']
    for row in compiled['sources']: assert sha(HERE / row['path']) == row['sha256Bytes']
    proof = out / 'proof'; assert not proof.exists(); proof.mkdir()
    media = out / 'media'; media.mkdir()
    synthetic = Image.new('RGB', (96, 64))
    for y in range(64):
        for x in range(96): synthetic.putpixel((x, y), ((x * 3) % 256, (y * 4) % 256, (x + y) % 256))
    synthetic.save(media / 'source.png')
    exif = Image.Exif(); exif[271] = 'Task fixture camera'; exif[272] = 'Fixture model'; exif[306] = '2026:10:01 10:00:00'
    synthetic.save(media / 'tagged-input.jpg', quality=95, exif=exif)
    ffmpeg = REPO / 'desktop/resources/common/native/windows-x64/ffmpeg.exe'
    ffprobe = REPO / 'desktop/resources/common/native/windows-x64/ffprobe.exe'
    command = [str(ffmpeg), '-hide_banner', '-nostdin', '-y', '-f', 'lavfi', '-i', 'testsrc=size=96x64:rate=15',
               '-t', '0.8', '-c:v', 'mpeg4', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', str(media / 'source.mp4')]
    r = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=25)
    write(out / 'ffmpeg.log', r.stdout + r.stderr); r.check_returncode()
    # A standards-shaped free box enlarges the real MP4 enough to exercise the
    # bounded-copy cancellation checkpoint without a fabricated receiver/file.
    with (media / 'source.mp4').open('ab') as stream:
        stream.write(struct.pack('>I', 1024 * 1024 + 8) + b'free' + bytes(1024 * 1024))
    native_home = Path(tempfile.mkdtemp(prefix='bp-motion-'))
    env = os.environ.copy()
    for name in ['APPDATA', 'LOCALAPPDATA', 'USERPROFILE', 'TEMP', 'TMP']:
        folder = native_home / name.lower(); folder.mkdir(); env[name] = str(folder)
    values = ['-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '-Duser.home=' + str(native_home),
        '-Djava.io.tmpdir=' + str(native_home / 'temp'), '-cp', ';'.join([str(compiled_out / 'candidate-and-fixture.jar')] + [row['path'] for row in cp]),
        'com.bilipai.desktop.ui.gallerymotionphotoproof.PackingFixtureAcceptanceKt', proof, media / 'source.png', media / 'source.mp4', media / 'tagged-input.jpg']
    argsfile(out / 'run.args', values)
    r = subprocess.run([str(c.JAVA), '@' + str(out / 'run.args')], cwd=REPO, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=60)
    write(out / 'run.log', r.stdout + r.stderr); print(r.stdout + r.stderr); r.check_returncode()
    result = json.loads((proof / 'result.json').read_text()); assert result['passed']
    # Independent physical readback validates the actual MP4 tail, JPEG decode
    # and XML metadata; the core's own output length is not its only oracle.
    mp4 = (media / 'source.mp4').read_bytes()
    observations = []
    for filename in ['motion-photo.jpg', 'packing-preserves-input-exif.jpg', 'existing.jpg', 'exif-failure-fallback.jpg']:
        body = (proof / filename).read_bytes()
        assert body[-len(mp4):] == mp4
        xmp_start = body.index(b'<x:xmpmeta'); xmp_end = body.index(b'</x:xmpmeta>') + len(b'</x:xmpmeta>')
        import xml.etree.ElementTree as ET
        xml = ET.fromstring(body[xmp_start:xmp_end])
        props = list(xml.iter('{http://www.w3.org/1999/02/22-rdf-syntax-ns#}Description'))[0].attrib
        assert props['{http://ns.google.com/photos/1.0/camera/}MicroVideoOffset'] == str(len(mp4))
        items = list(xml.iter('{http://ns.google.com/photos/1.0/container/}Item'))
        assert items[-1].attrib['{http://ns.google.com/photos/1.0/container/item/}Length'] == str(len(mp4))
        with Image.open(proof / filename) as img:
            assert img.size == (96, 64)
            if filename == 'packing-preserves-input-exif.jpg': assert img.getexif()[271] == 'Task fixture camera'
        observations.append({'path': filename, 'sha256Bytes': sha(proof / filename), 'jpegDecoded': [96, 64], 'mp4TailSha256Bytes': hashlib.sha256(body[-len(mp4):]).hexdigest(), 'xmpVideoSizeMatchesActualTail': True})
    extracted = proof / 'independently-extracted-tail.mp4'; extracted.write_bytes((proof / 'motion-photo.jpg').read_bytes()[-len(mp4):])
    probe_command = [str(ffprobe), '-v', 'error', '-show_streams', '-show_format', '-of', 'json', str(extracted)]
    probe = subprocess.run(probe_command, capture_output=True, text=True, encoding='utf-8', timeout=25); probe.check_returncode()
    write(out / 'ffprobe-tail.json', probe.stdout)
    probe_value = json.loads(probe.stdout)
    assert probe_value['streams'][0]['width'] == 96 and probe_value['streams'][0]['height'] == 64
    assert float(probe_value['format']['duration']) > 0
    for row in cp: assert sha(Path(row['path'])) == row['sha256Bytes']
    save(out / 'accepted-evidence.json', {**result, 'historicalActualMainManifestSha256Bytes': sha(manifestfile),
        'orderedRuntimeCpSha256Bytes': sha(cpfile), 'runtimeCpEntries': 89, 'productionOverrides': 0,
        'candidateJarSha256Bytes': sha(compiled_out / 'candidate-and-fixture.jar'), 'compiledAttempt': compiled_out.name, 'independentMediaReadback': observations,
        'ffprobeActualExtractedSyntheticVideo': True, 'nativeExtractionDirectory': str(native_home),
        'nativeTools': [{'path': str(p.relative_to(REPO)), 'sha256Bytes': sha(p)} for p in [ffmpeg, ffprobe]],
        'ffmpegCommand': command, 'ffprobeCommand': probe_command, 'ExifWriterAcceptance': False, 'approvedXmpFixOnly': 'delete three duplicate GCamera modern attribute lines', 'WindowsMotionPhotoContainerFormatAccepted': True,
        'WindowsPhotosMotionPhotoRecognition': False, 'noInstalledMainClaim': True})
    print('PASS independent JPEG/XML/EXIF-input readback and FFprobe actual synthetic MP4 tail; EXIF writer remains unaccepted')
else: raise ValueError(phase)
