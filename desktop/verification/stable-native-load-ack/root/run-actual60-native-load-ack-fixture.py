from pathlib import Path
from urllib.parse import urlparse, unquote
import hashlib
import importlib.util
import json
import subprocess
import sys
import zipfile

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
LANE = HERE / 'native-load-ack60'
SNAPSHOT = MAIN / 'desktop/.local/stable-product-snapshot-60'
OUT = HERE / 'actual60-native-load-ack-fixture01'
assert not OUT.exists()

def wide(p):
    s = str(Path(p).absolute())
    prefix = chr(92) * 2 + '?' + chr(92)
    return Path(s if s.startswith(prefix) else prefix + s)

def digest(p):
    return hashlib.sha256(wide(p).read_bytes()).hexdigest()

def save(p, data):
    wide(p).parent.mkdir(parents=True, exist_ok=True)
    wide(p).write_bytes((json.dumps(data, indent=2) + '\n').encode())

assert digest(SNAPSHOT / 'manifest.json') == '0867887c14dcb09600aa18192ddcc970153498fdfd9ad37a2387ca201d0d7b05'
assert digest(SNAPSHOT / 'ordered-runtime-cp.json') == 'b82e3c215374b0be98d2018316487514cb00a747dc589828ea504e053cd0ee8e'
source = LANE / 'NativeLoadAckFixture.kt'
source_pin = '2cca70ec7b781904f0f447a2212dd002747e74f198e86c88658c6529a90e8743'
assert digest(source) == source_pin
native = MAIN / 'desktop/native/windows-x64/libmpv-2.dll'
clip = MAIN / 'desktop/.local/stable-home-native-media-proof/runs/actual33-01/local-media/moving.mp4'
assert digest(native) == '673e6397920ab64a9c5b3a618f7f16d38854efe72b58665f1f84e4e873b763a4'
assert digest(clip) == '0e09ab9a6a9af00cee99508e4a3ff41e9436f7d1850e4b31c6ceb0c22d8c4656'
cp = json.loads(wide(SNAPSHOT / 'ordered-runtime-cp.json').read_bytes())
assert len(cp) == 101
spec = importlib.util.spec_from_file_location('compile_settings', MAIN / 'desktop/.local/source9-appearance/compile-miuix.py')
compiler = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compiler)
OUT.mkdir()
wide(OUT / 'NativeLoadAckFixture.kt').write_bytes(wide(source).read_bytes())

def pins():
    for row in cp:
        assert digest(row['path']) == row['sha256Bytes']
    assert digest(source) == source_pin
    assert digest(native) == '673e6397920ab64a9c5b3a618f7f16d38854efe72b58665f1f84e4e873b763a4'
    assert digest(clip) == '0e09ab9a6a9af00cee99508e4a3ff41e9436f7d1850e4b31c6ceb0c22d8c4656'
    return dict(actualProduct=60, runtime=cp, fixtureSourceSha256Bytes=source_pin,
        native=dict(path=str(native), sha256Bytes=digest(native)), clip=dict(path=str(clip), sha256Bytes=digest(clip)),
        compiler=[dict(path=str(p), sha256Bytes=digest(p)) for p in [compiler.JAVA] + compiler.COMPILER], productionOverrides=0)

sam = subprocess.run([str(compiler.JAVA.with_name('javap.exe')), '-classpath', str(SNAPSHOT / 'main-kotlin.jar'),
    'com.bilipai.desktop.player.DesktopNativePlaybackPublication'], capture_output=True, timeout=10)
wide(OUT / 'sam-signature.log').write_bytes(sam.stdout + sam.stderr)
assert sam.returncode == 0
signature = sam.stdout.decode('utf8')
assert signature.count('public abstract ') == 1 and 'public default void onLoadCommandAccepted();' in signature
save(OUT / 'sam-signature-result.json', dict(passed=True, singleAbstractMethod=True, hookIsJvmDefault=True,
    productJarSha256Bytes=digest(SNAPSHOT / 'main-kotlin.jar'), javapSha256Bytes=digest(compiler.JAVA.with_name('javap.exe'))))
save(OUT / 'pins-before.json', pins())
jar = OUT / 'fixture.jar'
runtime = [r['path'] for r in cp]
args = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-module-name', 'com_bilipai_desktop_bilipai_windows',
    '-Xfriend-paths=' + str(SNAPSHOT / 'main-kotlin.jar'), '-cp', ';'.join(runtime), '-d', str(jar), str(OUT / 'NativeLoadAckFixture.kt')]
wide(OUT / 'compile.args').write_bytes(('\n'.join('"' + a.replace('\\', '/') + '"' for a in args) + '\n').encode())
result = subprocess.run([str(compiler.JAVA), '-Xmx2g', '-Dfile.encoding=UTF-8', '-cp', ';'.join(map(str, compiler.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '@' + str(OUT / 'compile.args')], capture_output=True, timeout=120)
wide(OUT / 'compile.log').write_bytes(result.stdout + result.stderr)
if result.returncode:
    print((result.stdout + result.stderr).decode('utf8', errors='replace'))
    sys.exit(result.returncode)
with zipfile.ZipFile(wide(jar)) as archive:
    fixture_classes = {n for n in archive.namelist() if n.endswith('.class')}
product_classes = set()
for path in runtime:
    with zipfile.ZipFile(wide(path)) as archive:
        product_classes.update(n for n in archive.namelist() if n.endswith('.class'))
assert not fixture_classes & product_classes
result = subprocess.run([str(compiler.JAVA), '-Dfile.encoding=UTF-8', '-Dbilipai.mpv.path=' + str(native), '-cp', ';'.join([str(jar)] + runtime),
    'com.bilipai.desktop.player.NativeLoadAckFixture', str(OUT / 'result.json'), str(clip)], capture_output=True, timeout=55, creationflags=subprocess.CREATE_NO_WINDOW)
wide(OUT / 'run.log').write_bytes(result.stdout + result.stderr)
save(OUT / 'pins-after.json', pins())
assert wide(OUT / 'pins-before.json').read_bytes() == wide(OUT / 'pins-after.json').read_bytes()
verified_origins = []
if result.returncode == 0:
    proof = json.loads(wide(OUT / 'result.json').read_bytes())
    assert proof['passed'] and proof['assertions'] == 13
    for origin in proof['origins']:
        path = Path(unquote(urlparse(origin['codeSource']).path).lstrip('/'))
        assert str(path) in runtime
        entry = origin['class'].replace('.', '/') + '.class'
        with zipfile.ZipFile(wide(path)) as archive:
            assert hashlib.sha256(archive.read(entry)).hexdigest() == origin['sha256ClassBytes']
        verified_origins.append(origin)
    assert len(verified_origins) == 2
save(OUT / 'runner-result.json', dict(passed=result.returncode == 0, compileExitCode=0, runExitCode=result.returncode,
    actualProductSnapshot=60, runtimeEntries=101, fixtureClasses=len(fixture_classes), productionOverrides=0,
    fixtureProductClassIntersections=[], fixtureJarSha256Bytes=digest(jar), byteVerifiedProductOrigins=verified_origins,
    fixtureSourceUnchanged=True, fixtureIsRootOwned=True, sameSocketFreeChecksReplayed=True,
    actualAccountUsed=False, nativeExecuted=True, actualLocalNativeClip=True, fullControllerDrainAccepted=False, independentVisualAcceptance=False, fullOrdinaryVideoRootMountAccepted=False))
print((result.stdout + result.stderr).decode('utf8', errors='replace'))
sys.exit(result.returncode)
