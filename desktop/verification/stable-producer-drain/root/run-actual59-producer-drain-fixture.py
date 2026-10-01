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
LANE = HERE / 'producer-drain59'
SNAPSHOT = MAIN / 'desktop/.local/stable-product-snapshot-59'
OUT = HERE / 'actual59-producer-drain-fixture01'
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

assert digest(SNAPSHOT / 'manifest.json') == '92a2b4ac0576fe52878d3957b14e438ebe78067b87c77e42fee424efc960bd5a'
assert digest(SNAPSHOT / 'ordered-runtime-cp.json') == 'b8cf1abf30f86d3433efc18db36a0eef6e7436692f417f8d1883e896417c7bbc'
source = LANE / 'ProducerDrainFixture.kt'
source_pin = '2143dfa393a664898846efc2ee943b9806fd193b01c4283106f3ae651ff7b4c3'
assert digest(source) == source_pin
cp = json.loads(wide(SNAPSHOT / 'ordered-runtime-cp.json').read_bytes())
assert len(cp) == 101
spec = importlib.util.spec_from_file_location('compile_settings', MAIN / 'desktop/.local/source9-appearance/compile-miuix.py')
compiler = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compiler)
OUT.mkdir()
wide(OUT / 'ProducerDrainFixture.kt').write_bytes(wide(source).read_bytes())

def pins():
    for row in cp:
        assert digest(row['path']) == row['sha256Bytes']
    assert digest(source) == source_pin
    return dict(actualProduct=59, runtime=cp, fixtureSourceSha256Bytes=source_pin,
        compiler=[dict(path=str(p), sha256Bytes=digest(p)) for p in [compiler.JAVA] + compiler.COMPILER], productionOverrides=0)

save(OUT / 'pins-before.json', pins())
jar = OUT / 'fixture.jar'
runtime = [r['path'] for r in cp]
args = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-module-name', 'com_bilipai_desktop_bilipai_windows',
    '-Xfriend-paths=' + str(SNAPSHOT / 'main-kotlin.jar'), '-cp', ';'.join(runtime), '-d', str(jar), str(OUT / 'ProducerDrainFixture.kt')]
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
    'com.bilipai.desktop.player.ProducerDrainFixture', str(OUT / 'result.json')], capture_output=True, timeout=40)
wide(OUT / 'run.log').write_bytes(result.stdout + result.stderr)
save(OUT / 'pins-after.json', pins())
assert wide(OUT / 'pins-before.json').read_bytes() == wide(OUT / 'pins-after.json').read_bytes()
verified_origins = []
if result.returncode == 0:
    proof = json.loads(wide(OUT / 'result.json').read_bytes())
    assert proof['passed'] and proof['assertions'] == 15
    for origin in proof['origins']:
        path = Path(unquote(urlparse(origin['codeSource']).path).lstrip('/'))
        assert str(path) in runtime
        entry = origin['class'].replace('.', '/') + '.class'
        with zipfile.ZipFile(wide(path)) as archive:
            assert hashlib.sha256(archive.read(entry)).hexdigest() == origin['sha256ClassBytes']
        verified_origins.append(origin)
    assert len(verified_origins) == 2
save(OUT / 'runner-result.json', dict(passed=result.returncode == 0, compileExitCode=0, runExitCode=result.returncode,
    actualProductSnapshot=59, runtimeEntries=101, fixtureClasses=len(fixture_classes), productionOverrides=0,
    fixtureProductClassIntersections=[], fixtureJarSha256Bytes=digest(jar), byteVerifiedProductOrigins=verified_origins,
    fixtureSourceUnchanged=True, fixtureIsRootOwned=True, sameSocketFreeChecksReplayed=True,
    actualAccountUsed=False, nativeExecuted=False, fullOrdinaryVideoRootMountAccepted=False, fullControllerDrainAccepted=False, memoryWorkOnly=True))
print((result.stdout + result.stderr).decode('utf8', errors='replace'))
sys.exit(result.returncode)
