"""Actual product event/lease closure, compiling only the existing task fixture."""
from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, tempfile, zipfile
sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
HERE = Path(__file__).resolve().parent
REPO = next(p for p in HERE.parents if (p/'.git').exists())
OUT = HERE / 'lease-proof01'
assert not OUT.exists(), 'Use a fresh attempt; do not overwrite evidence'
SNAPSHOT = HERE / 'main-product-snapshot-01/manifest.json'
CP = SNAPSHOT.with_name('ordered-runtime-cp.json')
FIXTURE = HERE / 'NativeShareLeaseFixture.kt'
def ext(path):
    value = str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)
def sha(path): return hashlib.sha256(ext(path).read_bytes()).hexdigest()
def read(path): return json.loads(ext(path).read_text(encoding='utf-8'))
def save(path, value): ext(path).write_text(json.dumps(value, indent=2)+'\n', encoding='utf-8', newline='\n')
def argfile(path, values):
    ext(path).write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n',encoding='utf-8',newline='\n')
assert sha(SNAPSHOT) == '9f58369713e6738bea2a6cde03c586768fa414188a82f127b0b0771ca8e50151'
assert sha(CP) == '334d1947e200a659f06ab4002bf4b94870bb6ffa369a72a994bd1afbe69d88a2'
assert sha(FIXTURE) == 'fdcbfb52bf0c2bc97d0385c8b6c961dde8896fcf9bbdc537207bb96c27f38d66'
snapshot = read(SNAPSHOT); deps = read(CP)
if not isinstance(deps, list): deps = deps['entries']
assert len(deps) == 89
entries = set()
for row in deps:
    assert sha(row['path']) == row['sha256Bytes']
    with zipfile.ZipFile(ext(row['path'])) as jar: entries.update(jar.namelist())
kotlin = next(row['path'] for row in snapshot['artifacts'] if 'kotlin' in row['source'])
spec = importlib.util.spec_from_file_location('lease_actual_compiler', HERE.parent/'source9-appearance/compile-miuix.py')
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
OUT.mkdir(); frozen = OUT/'NativeShareLeaseFixture.kt'; frozen.write_bytes(FIXTURE.read_bytes())
fixture_jar = OUT/'fixture-only.jar'; cp = [row['path'] for row in deps]
compile_values = ['-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,c.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','21',
    '-cp',';'.join(cp),'-Xfriend-paths='+kotlin,'-module-name','native_share_actual_product_lease_proof',
    '-d',str(fixture_jar),str(frozen)]
argfile(OUT/'compile.args',compile_values)
r = subprocess.run([str(c.JAVA),'@'+str(OUT/'compile.args')],capture_output=True,encoding='utf-8',errors='replace',timeout=90)
(OUT/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8',newline='\n'); print(r.stdout+r.stderr,flush=True); r.check_returncode()
with zipfile.ZipFile(ext(fixture_jar)) as jar:
    classes = [name for name in jar.namelist() if name.endswith('.class')]
    assert classes and not set(classes).intersection(entries)
    assert all(name.startswith('com/bilipai/desktop/diagnostics/leaseproof/') for name in classes)
    save(OUT/'fixture-class-identities.json',[{'entry':name,'sha256Bytes':hashlib.sha256(jar.read(name)).hexdigest()} for name in jar.namelist()])
scratch = Path(tempfile.mkdtemp(prefix='bp-ns-lease-'))
env = os.environ.copy()
for key in ['LOCALAPPDATA','APPDATA','USERPROFILE','TEMP','TMP']:
    directory = scratch/key.lower(); directory.mkdir(); env[key] = str(directory)
env.pop('JAVA_TOOL_OPTIONS',None)
results = []
for case in ['repeat-terminal','missing-events','before-data','clear-queued','clear-live-retry','locked-copy','budget','restore-retirement']:
    destination = OUT/case; destination.mkdir()
    values = ['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.security.manager=allow',
        '-Duser.home='+str(scratch),'-Djava.io.tmpdir='+str(scratch/'temp'),'-cp',';'.join([str(fixture_jar)]+cp),
        'com.bilipai.desktop.diagnostics.leaseproof.NativeShareLeaseFixtureKt',case,str(destination)]
    file = OUT/(case+'.args'); argfile(file,values)
    r = subprocess.run([str(c.JAVA),'@'+str(file)],env=env,capture_output=True,encoding='utf-8',errors='replace',timeout=90)
    (OUT/(case+'.log')).write_text(r.stdout+r.stderr,encoding='utf-8',newline='\n'); print(r.stdout+r.stderr,flush=True); r.check_returncode()
    result = read(destination/'result.json'); assert result['passed'] and result['networkAttempts']==0
    results.append(result)
for row in deps: assert sha(row['path'])==row['sha256Bytes']
save(OUT/'accepted-evidence.json',{'passed':True,'snapshotSha256Bytes':sha(SNAPSHOT),'runtimeCpSha256Bytes':sha(CP),
    'fixtureSourceSha256Bytes':sha(frozen),'fixtureJarSha256Bytes':sha(fixture_jar),
    'orderedRuntimeDependencies':deps,'productionOverrides':0,'emittedClassOverlap':0,'freshJvms':8,
    'checks':sum(len(row['checks']) for row in results),'results':results,'syntheticSdkEventPort':True,
    'MainRootWindowExecuted':False,'HWND':False,'ShareUI':False,'receiver':False,'packageAcceptance':False})
print('PASS 8 actual product lease scenarios, zero product overrides',flush=True)
