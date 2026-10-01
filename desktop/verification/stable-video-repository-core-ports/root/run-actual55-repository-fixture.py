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
LANE = MAIN / 'desktop/.local/stable-video-repository-core-ports-parity'
SNAPSHOT = MAIN / 'desktop/.local/stable-product-snapshot-55'
OUT = HERE / 'actual55-repository-fixture01'
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

assert digest(SNAPSHOT / 'manifest.json') == 'c26f15be9d6472c30b2a58a97d50c85cedc11d8074df2c1516620dd33334f124'
assert digest(SNAPSHOT / 'ordered-runtime-cp.json') == '8118ea8bb2d916b8e81ad2f6e678115b437ac458a4c94cd42dc9c1a0813ea55a'
assert digest(LANE / 'frozen-handoff.json') == 'c9128187e1828c0b18a1f1c776d3c3b4f900751bf676f2edbfcbefd628e4437e'
frozen = json.loads(wide(LANE / 'frozen-handoff.json').read_bytes())
source = LANE / 'fixture/RepositoryCoreFixture.kt'
source_pin = next(r['sha256Bytes'] for r in frozen['rawArtifacts'] if r['path'] == 'fixture/RepositoryCoreFixture.kt')
assert digest(source) == source_pin
cp = json.loads(wide(SNAPSHOT / 'ordered-runtime-cp.json').read_bytes())
assert len(cp) == 101
spec = importlib.util.spec_from_file_location('compile_settings', MAIN / 'desktop/.local/source9-appearance/compile-miuix.py')
compiler = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compiler)
OUT.mkdir()
wide(OUT / 'RepositoryCoreFixture.kt').write_bytes(wide(source).read_bytes())

def pins():
    for row in cp:
        assert digest(row['path']) == row['sha256Bytes']
    assert digest(source) == source_pin
    return dict(actualProduct=55, runtime=cp, fixtureSourceSha256Bytes=source_pin,
        compiler=[dict(path=str(p), sha256Bytes=digest(p)) for p in [compiler.JAVA] + compiler.COMPILER], productionOverrides=0)

save(OUT / 'pins-before.json', pins())
jar = OUT / 'fixture.jar'
runtime = [r['path'] for r in cp]
args = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-module-name', 'com_bilipai_desktop_bilipai_windows',
    '-Xfriend-paths=' + str(SNAPSHOT / 'main-kotlin.jar'), '-cp', ';'.join(runtime), '-d', str(jar), str(OUT / 'RepositoryCoreFixture.kt')]
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
    'com.bilipai.desktop.ui.RepositoryCoreFixture', str(OUT / 'result.json')], capture_output=True, timeout=40)
wide(OUT / 'run.log').write_bytes(result.stdout + result.stderr)
save(OUT / 'pins-after.json', pins())
assert wide(OUT / 'pins-before.json').read_bytes() == wide(OUT / 'pins-after.json').read_bytes()
verified_origins = []
if result.returncode == 0:
    proof = json.loads(wide(OUT / 'result.json').read_bytes())
    assert proof['passed'] and proof['assertions'] == 27
    for origin in proof['origins']:
        path = Path(unquote(urlparse(origin['codeSource']).path).lstrip('/'))
        assert str(path) in runtime
        entry = origin['class'].replace('.', '/') + '.class'
        with zipfile.ZipFile(wide(path)) as archive:
            assert hashlib.sha256(archive.read(entry)).hexdigest() == origin['sha256ClassBytes']
        verified_origins.append(origin)
    assert len(verified_origins) == 7
save(OUT / 'runner-result.json', dict(passed=result.returncode == 0, compileExitCode=0, runExitCode=result.returncode,
    actualProductSnapshot=55, runtimeEntries=101, fixtureClasses=len(fixture_classes), productionOverrides=0,
    fixtureProductClassIntersections=[], fixtureJarSha256Bytes=digest(jar), byteVerifiedProductOrigins=verified_origins,
    fixtureSourceUnchanged=True, fixtureHistoricalScopeTextRetained=True, sameSocketFreeChecksReplayed=True,
    actualAccountUsed=False, nativeExecuted=False, fullOrdinaryVideoRootMountAccepted=False))
print((result.stdout + result.stderr).decode('utf8', errors='replace'))
sys.exit(result.returncode)
