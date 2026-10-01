"""Fixture-only compiler and gated actual-product runner. NEVER execute a production overlay."""
from pathlib import Path
import argparse, hashlib, importlib.util, json, subprocess, sys, urllib.parse, zipfile
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
PREPARED = MAIN / 'desktop/.local/stable-home-platform-media-parity'
HOME = MAIN / 'desktop/.local/stable-home-page-parity/classes-full-07'
NATIVE = MAIN / 'desktop/native/windows-x64'
EXPECTED_NATIVE = {
    'libmpv-2.dll': '673e6397920ab64a9c5b3a618f7f16d38854efe72b58665f1f84e4e873b763a4',
    'ffmpeg.exe': '9c60da6c0b083110d59084ea39f60ae149aa3e031c3b4bb4f573fafa1c1e7cea',
    'ffprobe.exe': '67176fa62f89f94c3bcd379fd05677a25651569a2eb8880ec2194e62c82be412',
}
REQUIRED_CLASSES = [
    'com.bilipai.desktop.player.MpvPlayer', 'com.bilipai.desktop.player.MpvNative',
    'com.bilipai.desktop.player.MpvSoftwareRenderer', 'com.bilipai.desktop.player.MpvSoftwareTarget',
    'com.bilipai.desktop.player.MpvSoftwareFrame', 'com.bilipai.desktop.ui.DesktopHomeMediaLifetime',
    'com.bilipai.desktop.ui.DesktopHomeMediaLease', 'com.bilipai.desktop.ui.DesktopHomeOwnedMediaKt',
    'com.bilipai.desktop.data.DesktopSessionStore',
]

def safe(path):
    path = str(Path(path).absolute())
    return Path(path if path.startswith('\\\\?\\') else '\\\\?\\' + path)

def sha(path):
    digest = hashlib.sha256()
    with safe(path).open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''): digest.update(chunk)
    return digest.hexdigest()

def save(path, value):
    write(path, json.dumps(value, ensure_ascii=False, indent=2) + '\n')

def write(path, value):
    safe(path.parent).mkdir(parents=True, exist_ok=True)
    safe(path).write_text(value, encoding='utf-8', newline='\n')

def compiler():
    spec = importlib.util.spec_from_file_location('fixture_compiler', MAIN / 'desktop/.local/source9-appearance/compile-miuix.py')
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value

def jar_classes(path):
    with zipfile.ZipFile(safe(path)) as archive:
        return {name for name in archive.namelist() if name.endswith('.class')}

def verify_cp(snapshot, manifest_sha, cp_sha):
    assert sha(snapshot / 'manifest.json') == manifest_sha, 'Snapshot manifest mismatch'
    assert sha(snapshot / 'ordered-runtime-cp.json') == cp_sha, 'Ordered CP mismatch'
    cp = json.loads(safe(snapshot / 'ordered-runtime-cp.json').read_text(encoding='utf-8'))
    for item in cp: assert sha(item['path']) == item['sha256Bytes'], item['path']
    return cp

def pins(cp, overlays):
    values = dict(runtime=cp, fixtureSource=dict(path=str(HERE / 'NativeHomeMediaFixture.kt'), sha256Bytes=sha(HERE / 'NativeHomeMediaFixture.kt')))
    values['runnerSource'] = dict(path=str(HERE / 'run.py'), sha256Bytes=sha(HERE / 'run.py'))
    c = compiler()
    values['compilerToolchain'] = [dict(path=str(path), sha256Bytes=sha(path)) for path in [c.JAVA, c.PLUGIN] + c.COMPILER]
    values['declaredCompileOnlyPreparedDependencies'] = overlays
    values['nativeFiles'] = [dict(path=str(NATIVE / name), sha256Bytes=sha(NATIVE / name)) for name in EXPECTED_NATIVE]
    values['nativeProvenance'] = [dict(path=str(path), sha256Bytes=sha(path)) for path in [MAIN / 'desktop/third-party/libmpv/SOURCES.json', NATIVE / 'libmpv-provenance.json', NATIVE / 'ffmpeg-provenance.json'] if path.exists()]
    return values

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['prepared-compile', 'actual-run'])
    parser.add_argument('run_name')
    parser.add_argument('--snapshot', type=Path)
    parser.add_argument('--manifest-sha')
    parser.add_argument('--cp-sha')
    options = parser.parse_args()
    assert options.run_name and all(c.isalnum() or c in '-_' for c in options.run_name)
    overlays = []
    if options.mode == 'prepared-compile':
        assert not any([options.snapshot, options.manifest_sha, options.cp_sha]), 'Prepared compile uses pinned historical compile dependencies'
        snapshot = MAIN / 'desktop/.local/stable-product-snapshot-30'
        cp = verify_cp(snapshot, 'e69b7b08805fc7a6aaec8557e8ba1cfd2368b701f99ecb94edc672efd22b6b69', 'dcab5cb81f9c62929c07966f76fe3bfd54ee0e7b93614296c576b24f218071c0')
        candidate = PREPARED / 'runs/04/prepared-home-platform.jar'
        assert sha(candidate) == 'ec6c0e4a310bdfec4f10377ca65c7198c869073a1c9f5a426cea7291290cc4f0'
        home_classes = [dict(path=str(p.relative_to(HOME)), sha256Bytes=sha(p)) for p in sorted(HOME.rglob('*.class'))]
        assert len(home_classes) == 332
        overlays = [dict(path=str(candidate), sha256Bytes=sha(candidate)), dict(path=str(HOME), classPins=home_classes)]
        compile_cp = [str(candidate), str(HOME)] + [item['path'] for item in cp]
        friends = [str(snapshot / 'main-kotlin.jar'), str(candidate), str(HOME)]
    else:
        assert all([options.snapshot, options.manifest_sha, options.cp_sha]), 'Actual run requires Root-provided immutable graph pins'
        snapshot = options.snapshot.absolute()
        cp = verify_cp(snapshot, options.manifest_sha, options.cp_sha)
        compile_cp = [item['path'] for item in cp]
        friends = [str(snapshot / 'main-kotlin.jar')]
        assert str(snapshot / 'main-kotlin.jar') in compile_cp
        actual_classes = jar_classes(snapshot / 'main-kotlin.jar')
        missing = [name for name in REQUIRED_CLASSES if name.replace('.', '/') + '.class' not in actual_classes]
        assert not missing, 'Media not installed in this product snapshot; NO execution: ' + repr(missing)
        for name in REQUIRED_CLASSES:
            locations = [item['path'] for item in cp if name.replace('.', '/') + '.class' in jar_classes(item['path'])]
            assert locations == [str(snapshot / 'main-kotlin.jar')], (name, locations)
        assert not any('stable-home-platform-media-parity' in item or 'classes-full-07' in item for item in compile_cp), 'No prepared product overlay allowed at runtime'
    for name, expected in EXPECTED_NATIVE.items(): assert sha(NATIVE / name) == expected, name
    out = HERE / 'runs' / options.run_name
    assert not out.exists(), 'Never overwrite earlier run evidence'
    out.mkdir(parents=True)
    before = pins(cp, overlays); save(out / 'dependency-pins-before.json', before)
    source = out / 'source-inputs/NativeHomeMediaFixture.kt'
    safe(source.parent).mkdir(parents=True, exist_ok=True); safe(source).write_bytes(safe(HERE / 'NativeHomeMediaFixture.kt').read_bytes())
    c = compiler(); target = out / 'native-media-fixture.jar'
    args = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-cp', ';'.join(compile_cp), '-Xplugin=' + str(c.PLUGIN), '-Xfriend-paths=' + ','.join(friends), '-module-name', 'home_media_fixture', '-d', str(target), str(source)]
    write(out / 'compiler.args', '\n'.join('"' + str(arg).replace('\\', '/') + '"' for arg in args) + '\n')
    r = subprocess.run([str(c.JAVA), '-Xmx3g', '-cp', ';'.join(map(str, c.COMPILER)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '@' + str(out / 'compiler.args')], capture_output=True, text=True, encoding='utf-8', timeout=180)
    write(out / 'compiler.log', r.stdout + r.stderr)
    compile_result = dict(status='PASS' if r.returncode == 0 else 'FAIL', exitCode=r.returncode, mode=options.mode, fixtureOnly=True, nativeExecution=False, snapshotManifestSha256Bytes=sha(snapshot / 'manifest.json'), dependencyOverlayUsedForCompileOnly=bool(overlays))
    save(out / 'compile-result.json', compile_result)
    if r.returncode: print(r.stdout + r.stderr); return r.returncode
    own = jar_classes(target); dependencies = set()
    for item in cp: dependencies.update(jar_classes(item['path']))
    if overlays:
        dependencies.update(jar_classes(candidate)); dependencies.update(str(path.relative_to(HOME)).replace('\\', '/') for path in HOME.rglob('*.class'))
    overlap = sorted(own & dependencies); assert not overlap, overlap
    save(out / 'fixture-symbol-audit.json', dict(fixtureClassCount=len(own), fixtureClasses=sorted(own), productionClassIntersection=overlap, productionClassOverrides=0, fixtureJarSha256Bytes=sha(target)))
    if options.mode == 'prepared-compile':
        after = pins(cp, overlays); assert before == after; save(out / 'dependency-pins-after.json', after)
        print('PASS fixture-only prospective compile. NO Java fixture/native/FFmpeg execution.'); return 0

    media = out / 'local-media'; media.mkdir()
    for name, filter_graph in [('moving', 'testsrc2=size=160x90:rate=15'), ('blue', 'color=c=blue:s=160x90:r=15')]:
        movie = media / (name + '.mp4')
        command = [str(NATIVE / 'ffmpeg.exe'), '-nostdin', '-hide_banner', '-y', '-f', 'lavfi', '-i', filter_graph, '-t', '4', '-c:v', 'mpeg4', '-q:v', '2', '-pix_fmt', 'yuv420p', '-an', str(movie)]
        save(media / (name + '-generator-command.json'), command)
        generated = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', timeout=45)
        write(media / (name + '-generator.log'), generated.stdout + generated.stderr); assert generated.returncode == 0
        probe_command = [str(NATIVE / 'ffprobe.exe'), '-v', 'error', '-show_streams', '-show_format', '-of', 'json', str(movie)]
        probe = subprocess.run(probe_command, capture_output=True, text=True, encoding='utf-8', timeout=15)
        write(media / (name + '-ffprobe.json'), probe.stdout); write(media / (name + '-ffprobe.log'), probe.stderr); assert probe.returncode == 0
        metadata = json.loads(probe.stdout); streams = metadata['streams']; assert len(streams) == 1 and streams[0]['codec_type'] == 'video'
        assert (streams[0]['width'], streams[0]['height']) == (160, 90) and float(metadata['format']['duration']) >= 3.9
    save(media / 'media-pins.json', [dict(path=str(path), sha256Bytes=sha(path)) for path in sorted(media.glob('*.mp4'))])
    runtime_cp = [str(target)] + compile_cp
    command = [str(c.JAVA), '-Xmx2g', '-Djava.awt.headless=true', '-Djava.security.manager=allow', '-Dbilipai.mpv.path=' + str(NATIVE / 'libmpv-2.dll'), '-cp', ';'.join(runtime_cp), 'com.bilipai.desktop.ui.NativeHomeMediaFixtureKt', str(media / 'moving.mp4'), str(media / 'blue.mp4'), str(out / 'proof')]
    save(out / 'java-command.json', command)
    executed = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', timeout=100)
    write(out / 'runtime.log', executed.stdout + executed.stderr)
    save(out / 'runtime-exit.json', dict(exitCode=executed.returncode, productionClassOverrides=0, preparedClassOverlay=False))
    if executed.returncode: print(executed.stdout + executed.stderr); return executed.returncode
    result = json.loads(safe(out / 'proof/result.json').read_text(encoding='utf-8'))
    assert result['status'] == 'PASS' and result['productionClassOverrides'] == 0
    assert set(item['class'] for item in result['actualCodeSources']) == set(REQUIRED_CLASSES)
    with zipfile.ZipFile(safe(snapshot / 'main-kotlin.jar')) as actual:
        for item in result['actualCodeSources']:
            url = urllib.parse.urlparse(item['codeSource']); assert url.scheme == 'file'
            source_path = urllib.parse.unquote(url.path).lstrip('/')
            assert Path(source_path).resolve() == (snapshot / 'main-kotlin.jar').resolve(), item
            assert hashlib.sha256(actual.read(item['class'].replace('.', '/') + '.class')).hexdigest() == item['classSha256Bytes'], item
    after = pins(cp, overlays); assert before == after; save(out / 'dependency-pins-after.json', after)
    save(out / 'accepted.json', dict(status='PASS', productionClassOverrides=0, actualClassSourcesVerified=len(REQUIRED_CLASSES), snapshotManifestSha256Bytes=sha(snapshot / 'manifest.json'), orderedCpSha256Bytes=sha(snapshot / 'ordered-runtime-cp.json'), runtimeEntries=len(cp), resultSha256Bytes=sha(out / 'proof/result.json'), offscreenComposeOnly=True, realNativeWindow=False, externalNetwork=False, RootUiAccountPlaybackProof=False, firstFrameScope='Observed successfully copied CPU render frame, not physical monitor presentation'))
    print('PASS actual product-only native software-frame and isolated Compose proof.'); return 0

if __name__ == '__main__': raise SystemExit(main())
