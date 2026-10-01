from pathlib import Path
from urllib.parse import urlparse, unquote
import hashlib, importlib.util, json, subprocess, sys, zipfile

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
LANE = MAIN / 'desktop/.local/stable-video-native-owner-cancellation-parity'
SNAPSHOT = MAIN / 'desktop/.local/stable-product-snapshot-61'
OUT = HERE / 'actual61-initial-publication-fixture01'
assert not OUT.exists()
expected_manifest, expected_cp = sys.argv[1:]

def wide(p):
    s = str(Path(p).absolute()); prefix = chr(92)*2 + '?' + chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def read(p): return wide(p).read_bytes()
def digest(p): return hashlib.sha256(read(p)).hexdigest()
def save(p, obj):
    p = wide(p); p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes((json.dumps(obj, indent=2)+'\n').encode())

assert digest(SNAPSHOT/'manifest.json') == expected_manifest
assert digest(SNAPSHOT/'ordered-runtime-cp.json') == expected_cp
assert digest(LANE/'frozen-handoff.json') == '8ef0b3d2e03ac1b862b7b1ab6ee1a1274b374aee34ee8ed299c8ed9e418832df'
source = LANE/'fixture/InitialNativePublicationFixture.kt'
source_pin = '360a545b5cdd83acb1c29bc511fa8718f0aede1d2bffa18218fc4817613b26ae'
assert digest(source) == source_pin
cp = json.loads(read(SNAPSHOT/'ordered-runtime-cp.json')); assert len(cp) == 101
spec = importlib.util.spec_from_file_location('compile_settings', MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
compiler = importlib.util.module_from_spec(spec); spec.loader.exec_module(compiler)
OUT.mkdir(); wide(OUT/'InitialNativePublicationFixture.kt').write_bytes(read(source))

def pins():
    for row in cp: assert digest(row['path']) == row['sha256Bytes']
    assert digest(source) == source_pin
    return dict(actualProduct=61, runtime=cp, fixtureSourceSha256Bytes=source_pin,
        compiler=[dict(path=str(p),sha256Bytes=digest(p)) for p in [compiler.JAVA]+compiler.COMPILER], productionOverrides=0)

save(OUT/'pins-before.json', pins())
jar = OUT/'fixture.jar'; runtime = [r['path'] for r in cp]
args = ['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
    '-Xfriend-paths='+str(SNAPSHOT/'main-kotlin.jar'),'-cp',';'.join(runtime),'-d',str(jar),str(OUT/'InitialNativePublicationFixture.kt')]
wide(OUT/'compile.args').write_bytes(('\n'.join('"'+a.replace('\\','/')+'"' for a in args)+'\n').encode())
result = subprocess.run([str(compiler.JAVA),'-Xmx2g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,compiler.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(OUT/'compile.args')],capture_output=True,timeout=120)
wide(OUT/'compile.log').write_bytes(result.stdout+result.stderr)
if result.returncode:
    print((result.stdout+result.stderr).decode('utf8',errors='replace')); sys.exit(result.returncode)
with zipfile.ZipFile(wide(jar)) as z: fixture_classes = {n for n in z.namelist() if n.endswith('.class')}
product_classes = set()
for path in runtime:
    with zipfile.ZipFile(wide(path)) as z: product_classes.update(n for n in z.namelist() if n.endswith('.class'))
assert not fixture_classes & product_classes
result = subprocess.run([str(compiler.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join([str(jar)]+runtime),
    'com.bilipai.desktop.ui.InitialNativePublicationFixture',str(OUT/'result.json')],capture_output=True,timeout=40)
wide(OUT/'run.log').write_bytes(result.stdout+result.stderr)
save(OUT/'pins-after.json',pins())
assert read(OUT/'pins-before.json') == read(OUT/'pins-after.json')
origins = []
if result.returncode == 0:
    proof = json.loads(read(OUT/'result.json')); assert proof['passed'] and proof['assertions'] == 15
    for origin in proof['origins']:
        path = Path(unquote(urlparse(origin['codeSource']).path).lstrip('/')); assert str(path) in runtime
        entry = origin['class'].replace('.','/')+'.class'
        with zipfile.ZipFile(wide(path)) as z: origin['sha256ClassEntryBytes'] = hashlib.sha256(z.read(entry)).hexdigest()
        origins.append(origin)
    assert len(origins) == 5
save(OUT/'runner-result.json',dict(passed=result.returncode==0,compileExitCode=0,runExitCode=result.returncode,
    actualProductSnapshot=61,runtimeEntries=101,fixtureClasses=len(fixture_classes),productionOverrides=0,
    fixtureProductClassIntersections=[],fixtureJarSha256Bytes=digest(jar),pinnedProductOrigins=origins,
    fixtureSourceUnchanged=True,classBytesReportedByFixture=False,fixtureHistoricalScopeTextRetained=True,
    commandAndAckAreSynthetic=True,actualAccountUsed=False,nativeExecuted=False,fullOrdinaryVideoRootMountAccepted=False))
print((result.stdout+result.stderr).decode('utf8',errors='replace')); sys.exit(result.returncode)
