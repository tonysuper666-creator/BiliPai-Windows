"""Task-only actual EXIF writer proof: approved three dependencies, explicit 92 CP."""
from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, tempfile, zipfile
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
phase, attempt = sys.argv[1:3]
assert attempt.isalnum()
out = HERE / 'exif-runs' / attempt
cpfile, manifestfile = SNAP / 'ordered-runtime-cp.json', SNAP / 'manifest.json'
assert sha(manifestfile) == '4780b40c15bd0de96f01fe5f60086bd2e67684764d075d8af8f437a27b0887c5'
assert sha(cpfile) == 'c2f31a97c207af9d501d669897deebfee51f4edf9a12b1280df59b542ec4b6ab'
cp = json.loads(cpfile.read_text()); assert len(cp) == 89
for row in cp: assert sha(Path(row['path'])) == row['sha256Bytes'], row['path']
receipts = [HERE / 'dependencies/imaging-download-receipt.json', HERE / 'dependencies/transitive-graph-receipt.json']
pins = {}
for receipt in receipts:
    value = json.loads(receipt.read_text())
    for row in value['downloads']: pins[row['path']] = row['sha256Bytes']
extra = [HERE / 'dependencies' / name for name in ['commons-imaging-1.0.0-alpha6.jar', 'commons-io-2.19.0.jar', 'commons-lang3-3.17.0.jar']]
for path in extra: assert sha(path) == pins[path.name]
assert json.loads(receipts[1].read_text())['requiredChildGraphClosed']
cp92 = cp + [{'path': str(p), 'sha256Bytes': sha(p), 'source': 'task-approved official archive/Maven byte-equal runtime JAR'} for p in extra]
assert len(cp92) == 92
spec = importlib.util.spec_from_file_location('owned_gallery_exif_compiler', REPO / 'desktop/.local/source9-appearance/compile-miuix.py')
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
sources = sorted((HERE / 'generated-acceptance-final').rglob('*.kt')) + [
    HERE / 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicMotionPhotoFiles.kt',
    HERE / 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicMotionPhotoExif.kt', HERE / 'ExifFixture.kt']
assert len(sources) == 5
if phase == 'compile':
    assert not out.exists(); out.mkdir(parents=True)
    for p in sources: write(out / 'frozen-sources' / p.name, p.read_text(encoding='utf-8'))
    candidate = out / 'candidate-and-fixture.jar'
    main_kotlin = next(row['path'] for row in cp if row.get('source') == 'desktop/build/classes/kotlin/main')
    values = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-cp', ';'.join(row['path'] for row in cp92),
              '-Xfriend-paths=' + main_kotlin, '-module-name', 'dynamic_gallery_motion_photo_exif_parity', '-d', candidate] + sources
    argsfile(out / 'compiler.args', values)
    r = subprocess.run([str(c.JAVA), '-Dfile.encoding=UTF-8', '-Xmx2g', '-cp', ';'.join(map(str, c.COMPILER)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '@' + str(out / 'compiler.args')], capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=90)
    write(out / 'compile.log', r.stdout + r.stderr); print(r.stdout + r.stderr); r.check_returncode()
    product_entries = set()
    for row in cp92:
        with zipfile.ZipFile(ext(row['path'])) as jar: product_entries.update(jar.namelist())
    with zipfile.ZipFile(candidate) as jar: classes = [name for name in jar.namelist() if name.endswith('.class')]
    assert not set(classes) & product_entries
    save(out / 'compile-evidence.json', {'passed': True, 'productionOverrides': 0, 'candidateNewClasses': len(classes),
        'historicalActualMainManifestSha256Bytes': sha(manifestfile), 'actualOrdered89CpSha256Bytes': sha(cpfile),
        'actualOrdered92Classpath': cp92, 'sources': [{'path': str(p.relative_to(HERE)), 'sha256Bytes': sha(p)} for p in sources],
        'candidateJarSha256Bytes': sha(candidate), 'newMainIntegration': False, 'sharedGradle': False,
        'approvedXmpThreeAttributeAdaptation': True, 'EXIFWriterActualLibrary': True,
        'dependencyReceipts': [{'path': str(p.relative_to(HERE)), 'sha256Bytes': sha(p)} for p in receipts]})
    print('PASS task-only actual EXIF candidate compile; explicit 92 CP / zero existing class overlap')
elif phase == 'run':
    from PIL import Image
    evidence = json.loads((out / 'compile-evidence.json').read_text())
    assert sha(out / 'candidate-and-fixture.jar') == evidence['candidateJarSha256Bytes']
    for row in evidence['sources']: assert sha(HERE / row['path']) == row['sha256Bytes']
    proof = out / 'proof'; assert not proof.exists(); proof.mkdir()
    media = out / 'media'; media.mkdir()
    image = HERE / 'runs/03/media/source.png'; video = HERE / 'runs/03/media/source.mp4'
    assert (HERE / 'runs/03/accepted-evidence.json').exists()
    with Image.open(image) as synthetic:
        tags = Image.Exif()
        for key, value in {271:'Old existing manufacturer', 272:'Old existing model', 306:'1999:01:01 00:00:00',
                           270:'Other existing description', 315:'Other existing artist', 274:1}.items(): tags[key] = value
        tags[34665] = {36867:'1999:01:01 00:00:00', 37510:b'ASCII\x00\x00\x00Existing UserComment'}
        synthetic.save(media / 'original-other-exif.jpg', quality=95, exif=tags)
    home = Path(tempfile.mkdtemp(prefix='bp-exif-')); env = os.environ.copy()
    for name in ['APPDATA', 'LOCALAPPDATA', 'USERPROFILE', 'TEMP', 'TMP']:
        folder = home / name.lower(); folder.mkdir(); env[name] = str(folder)
    values = ['-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '-Duser.timezone=UTC', '-Duser.home=' + str(home),
              '-Djava.io.tmpdir=' + str(home / 'temp'), '-cp', ';'.join([str(out / 'candidate-and-fixture.jar')] + [r['path'] for r in cp92]),
              'com.bilipai.desktop.ui.gallerymotionphotoproof.ExifFixtureKt', proof, media / 'original-other-exif.jpg', image, video]
    argsfile(out / 'run.args', values)
    r = subprocess.run([str(c.JAVA), '@' + str(out / 'run.args')], cwd=REPO, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=60)
    write(out / 'run.log', r.stdout + r.stderr); print(r.stdout + r.stderr); r.check_returncode()
    result = json.loads((proof / 'result.json').read_text()); assert result['passed']
    def jpeg_sections(data):
        assert data[:2] == b'\xff\xd8'
        pos, segments = 2, []
        while True:
            start = pos; assert data[pos] == 255
            while data[pos] == 255: pos += 1
            marker = data[pos]; pos += 1
            size = int.from_bytes(data[pos:pos+2], 'big'); assert size >= 2
            end = pos + size
            if marker == 218:
                eoi = data.index(b'\xff\xd9', end)
                return segments, data[start:eoi+2], eoi+2
            segments.append((marker, data[pos+2:end])); pos = end
    def independent_pair(before, after):
        left, right = before.read_bytes(), after.read_bytes()
        ls, le, _ = jpeg_sections(left); rs, re, _ = jpeg_sections(right)
        assert le == re, 'lossless EXIF writer changed exact SOS/entropy/EOI bytes'
        sans_exif = lambda segs: [(m,p) for m,p in segs if not (m == 225 and p.startswith(b'Exif\0\0'))]
        assert sans_exif(ls) == sans_exif(rs), 'lossless EXIF changed another marker segment'
        with Image.open(before) as a, Image.open(after) as b:
            assert a.size == b.size == (96,64)
            assert a.convert('RGB').tobytes() == b.convert('RGB').tobytes()
        return {'before': str(before.relative_to(HERE)), 'after': str(after.relative_to(HERE)),
                'exactEntropySha256Bytes': hashlib.sha256(le).hexdigest(), 'otherJpegSegmentsEqual': True, 'decodedPixelsEqual': True}
    pairs = [independent_pair(media/'original-other-exif.jpg', proof/'four-tags-written.jpg'),
             independent_pair(proof/'jpeg95-before-exif.jpg', proof/'jpeg95-after-exif.jpg'),
             independent_pair(proof/'jpeg95-before-exif.jpg', proof/'windows-platform-identity.jpg')]
    with Image.open(proof/'four-tags-written.jpg') as output:
        tags = output.getexif(); sub = tags.get_ifd(34665)
        assert tags[271] == 'Declared synthetic manufacturer' and tags[272] == 'Declared synthetic model'
        assert tags[306] == sub[36867] == '2026:10:01 10:00:00'
        assert tags[270] == 'Other existing description' and tags[315] == 'Other existing artist' and tags[274] == 1
        assert sub[37510] == b'ASCII\x00\x00\x00Existing UserComment'
    actual_identity = json.loads((proof/'windows-platform-identity.json').read_text())
    with Image.open(proof/'windows-platform-identity.jpg') as output:
        tags = output.getexif()
        assert tags[271] == actual_identity['manufacturer'] and tags[272] == actual_identity['model']
    mp4 = video.read_bytes()
    combined = (proof/'motion-photo-with-real-exif.jpg').read_bytes()
    assert combined[-len(mp4):] == mp4
    _, _, jpeg_end = jpeg_sections(combined)
    assert combined[jpeg_end:] == mp4
    import xml.etree.ElementTree as ET
    start = combined.index(b'<x:xmpmeta'); stop = combined.index(b'</x:xmpmeta>')+len(b'</x:xmpmeta>')
    xml = ET.fromstring(combined[start:stop])
    description = list(xml.iter('{http://www.w3.org/1999/02/22-rdf-syntax-ns#}Description'))[0]
    assert description.attrib['{http://ns.google.com/photos/1.0/camera/}MicroVideoOffset'] == str(len(mp4))
    items = list(xml.iter('{http://ns.google.com/photos/1.0/container/}Item'))
    assert items[-1].attrib['{http://ns.google.com/photos/1.0/container/item/}Length'] == str(len(mp4))
    for row in cp92: assert sha(row['path']) == row['sha256Bytes']
    save(out/'accepted-evidence.json', {**result, 'historicalActualMainManifestSha256Bytes': sha(manifestfile),
        'actualOrdered89CpSha256Bytes': sha(cpfile), 'runtimeCpEntries': 92, 'productionOverrides': 0,
        'candidateJarSha256Bytes': sha(out/'candidate-and-fixture.jar'), 'independentPillowExifReadback': True,
        'independentLosslessJpegChecks': pairs, 'independentXmpXmlAndExactMp4Tail': True,
        'actualWindowsPlatformIdentity': actual_identity, 'nativeExtractionDirectory': str(home),
        'syntheticMediaSourcePins': [{'path': str(p.relative_to(HERE)), 'sha256Bytes': sha(p)} for p in [image,video,media/'original-other-exif.jpg']],
        'WindowsPhotosMotionPhotoRecognition': False, 'systemSHARE': False, 'noInstalledMainClaim': True})
    print('PASS independent EXIF four-tag and other-tag readback, exact JPEG entropy/pixels and XMP/MP4; explicit 92 CP')
else: raise ValueError(phase)
