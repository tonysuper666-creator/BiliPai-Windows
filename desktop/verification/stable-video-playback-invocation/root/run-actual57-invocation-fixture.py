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
LANE = MAIN / 'desktop/.local/stable-video-invocation-parity'
SNAPSHOT = MAIN / 'desktop/.local/stable-product-snapshot-57'
OUT = HERE / 'actual57-invocation-fixture01'
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

assert digest(SNAPSHOT / 'manifest.json') == 'e28b6b6e465c8a03e0a26eff6dc2c1dfe1b0aa133e5710eaf3f7ff3f5fe9d007'
assert digest(SNAPSHOT / 'ordered-runtime-cp.json') == '9f9e943b59d873e36d1a8a29825bd65f1189209724dd0c9f3eed4cf0b38537b4'
assert digest(LANE / 'frozen-handoff.json') == '6a3b07d40a24f7cddf3d5ced277cf6b1fa4c410877bb358294cada770c59fddf'
frozen = json.loads(wide(LANE / 'frozen-handoff.json').read_bytes())
source = LANE / 'fixture/PlaybackInvocationFixture.kt'
source_pin = next(r['sha256Bytes'] for r in frozen['files'] if r['path'] == 'fixture/PlaybackInvocationFixture.kt')
assert digest(source) == source_pin
cp = json.loads(wide(SNAPSHOT / 'ordered-runtime-cp.json').read_bytes())
assert len(cp) == 101
spec = importlib.util.spec_from_file_location('compile_settings', MAIN / 'desktop/.local/source9-appearance/compile-miuix.py')
compiler = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compiler)
OUT.mkdir()
wide(OUT / 'PlaybackInvocationFixture.kt').write_bytes(wide(source).read_bytes())

def pins():
    for row in cp:
        assert digest(row['path']) == row['sha256Bytes']
    assert digest(source) == source_pin
    return dict(actualProduct=57, runtime=cp, fixtureSourceSha256Bytes=source_pin,
        compiler=[dict(path=str(p), sha256Bytes=digest(p)) for p in [compiler.JAVA] + compiler.COMPILER], productionOverrides=0)

save(OUT / 'pins-before.json', pins())
jar = OUT / 'fixture.jar'
runtime = [r['path'] for r in cp]
args = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-module-name', 'com_bilipai_desktop_bilipai_windows',
    '-Xfriend-paths=' + str(SNAPSHOT / 'main-kotlin.jar'), '-cp', ';'.join(runtime), '-d', str(jar), str(OUT / 'PlaybackInvocationFixture.kt')]
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
result = subprocess.run([str(compiler.JAVA), '-Dfile.encoding=UTF-8', '-cp', ';'.join([str(jar)] + runtime),
    'com.bilipai.desktop.ui.PlaybackInvocationFixture', str(OUT / 'result.json')], capture_output=True, timeout=40)
wide(OUT / 'run.log').write_bytes(result.stdout + result.stderr)
save(OUT / 'pins-after.json', pins())
assert wide(OUT / 'pins-before.json').read_bytes() == wide(OUT / 'pins-after.json').read_bytes()
verified_origins = []
if result.returncode == 0:
    proof = json.loads(wide(OUT / 'result.json').read_bytes())
    assert proof['passed'] and proof['assertions'] == 16
    for origin in proof['origins']:
        path = Path(unquote(urlparse(origin['codeSource']).path).lstrip('/'))
        assert str(path) in runtime
        entry = origin['class'].replace('.', '/') + '.class'
        with zipfile.ZipFile(wide(path)) as archive:
            origin['sha256ClassEntryBytes'] = hashlib.sha256(archive.read(entry)).hexdigest()
        verified_origins.append(origin)
    assert len(verified_origins) == 5
save(OUT / 'runner-result.json', dict(passed=result.returncode == 0, compileExitCode=0, runExitCode=result.returncode,
    actualProductSnapshot=57, runtimeEntries=101, fixtureClasses=len(fixture_classes), productionOverrides=0,
    fixtureProductClassIntersections=[], fixtureJarSha256Bytes=digest(jar), pinnedProductOrigins=verified_origins,
    fixtureSourceUnchanged=True, fixtureHistoricalScopeTextRetained=True, sameSocketFreeChecksReplayed=True, mediaAcceptanceIsFixtureObserverOnly=True, classBytesReportedByFixture=False,
    actualAccountUsed=False, nativeExecuted=False, fullOrdinaryVideoRootMountAccepted=False))
print((result.stdout + result.stderr).decode('utf8', errors='replace'))
sys.exit(result.returncode)
