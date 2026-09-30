"""Actual Main default native transport, no ShareShow or window, zero product overrides."""
from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, tempfile, zipfile
sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
HERE=Path(__file__).resolve().parent
REPO=next(p for p in HERE.parents if (p/'.git').exists())
OUT=HERE/'default-native-proof01'
assert not OUT.exists(), 'Fresh attempt only; preserve historical observations'
SNAPSHOT=HERE/'main-product-snapshot-01/manifest.json'
CP=SNAPSHOT.with_name('ordered-runtime-cp.json')
FIXTURE=HERE/'DefaultNativeTransportFixture.kt'
RESOURCES=REPO/'desktop/resources/common'
DLL=RESOURCES/'native/windows-x64/bilipai-diagnostic-share.dll'
SOURCE=REPO/'desktop/native/diagnostic-share/DesktopDiagnosticShare.cpp'
APPROVAL=SOURCE.with_name('approved-development-build.json')
def ext(path):
 value=str(Path(path).absolute());prefix=chr(92)*2+'?'+chr(92)
 return Path(value if value.startswith(prefix) else prefix+value)
def sha(path):return hashlib.sha256(ext(path).read_bytes()).hexdigest()
def read(path):return json.loads(ext(path).read_text(encoding='utf-8'))
def save(path,value):ext(path).write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8',newline='\n')
def argfile(path,values):ext(path).write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n',encoding='utf-8',newline='\n')
assert sha(SNAPSHOT)=='9f58369713e6738bea2a6cde03c586768fa414188a82f127b0b0771ca8e50151'
assert sha(CP)=='334d1947e200a659f06ab4002bf4b94870bb6ffa369a72a994bd1afbe69d88a2'
assert sha(DLL)=='2a36fd5c59cd0957fa9934f1c634635db414b7bdc863b92a943edb53107b6514'
assert sha(SOURCE)=='1606f70eb26cbff4d4e3278703753b27d271996b4971dae2abf13fbd50d5d8ea'
assert sha(APPROVAL)=='499a28ba7dec7a34cad1c7e63a051aa33739dc52b701f9423cdffb3bc670c238'
snapshot=read(SNAPSHOT);deps=read(CP)
if not isinstance(deps,list):deps=deps['entries']
assert len(deps)==89
entries=set()
for row in deps:
 assert sha(row['path'])==row['sha256Bytes']
 with zipfile.ZipFile(ext(row['path'])) as jar:entries.update(jar.namelist())
kotlin=next(row['path'] for row in snapshot['artifacts'] if 'kotlin' in row['source'])
spec=importlib.util.spec_from_file_location('default_native_compiler',HERE.parent/'source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
toolpins=[dict(path=str(p),sha256Bytes=sha(p)) for p in [c.JAVA,*c.COMPILER]]
OUT.mkdir();frozen=OUT/'DefaultNativeTransportFixture.kt';ext(frozen).write_bytes(ext(FIXTURE).read_bytes())
fixture_jar=OUT/'fixture-only.jar';cp=[row['path'] for row in deps]
compile_values=['-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,c.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','21',
 '-cp',';'.join(cp),'-Xfriend-paths='+kotlin,'-module-name','default_native_actual_main_proof',
 '-d',str(fixture_jar),str(frozen)]
argfile(OUT/'compile.args',compile_values)
inputs=dict(snapshotSha256Bytes=sha(SNAPSHOT),runtimeCpSha256Bytes=sha(CP),fixtureSourceSha256Bytes=sha(frozen),
 sourceSha256Bytes=sha(SOURCE),approvalSha256Bytes=sha(APPROVAL),dllSha256Bytes=sha(DLL),tools=toolpins,
 orderedRuntimeDependencies=deps,nativeResourcesDirectory=str(RESOURCES),defaultMainTransport=True,syntheticTransportFactory=False)
save(OUT/'input-pins.json',inputs)
r=subprocess.run([str(c.JAVA),'@'+str(OUT/'compile.args')],capture_output=True,encoding='utf-8',errors='replace',timeout=90)
ext(OUT/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8',newline='\n');print(r.stdout+r.stderr,flush=True);r.check_returncode()
with zipfile.ZipFile(ext(fixture_jar)) as jar:
 classes=[name for name in jar.namelist() if name.endswith('.class')]
 assert classes and not set(classes).intersection(entries)
 assert all(name.startswith('com/bilipai/desktop/diagnostics/defaultnativeproof/') for name in classes)
 save(OUT/'fixture-class-identities.json',[dict(entry=name,sha256Bytes=hashlib.sha256(jar.read(name)).hexdigest()) for name in jar.namelist()])
scratch=Path(tempfile.mkdtemp(prefix='bp-default-native-'))
env=os.environ.copy()
for key in ['LOCALAPPDATA','APPDATA','USERPROFILE','TEMP','TMP']:
 directory=scratch/key.lower();directory.mkdir();env[key]=str(directory)
env.pop('JAVA_TOOL_OPTIONS',None)
results=[]
for case in ['missing-window','repeat-no-window','hash-mismatch','retired-owner']:
 destination=OUT/case;destination.mkdir()
 values=['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.security.manager=allow',
  '-Duser.home='+str(scratch),'-Djava.io.tmpdir='+str(scratch/'temp'),'-cp',';'.join([str(fixture_jar)]+cp),
  'com.bilipai.desktop.diagnostics.defaultnativeproof.DefaultNativeTransportFixtureKt',case,str(destination),str(RESOURCES)]
 file=OUT/(case+'.args');argfile(file,values)
 r=subprocess.run([str(c.JAVA),'@'+str(file)],env=env,capture_output=True,encoding='utf-8',errors='replace',timeout=90)
 ext(OUT/(case+'.log')).write_text(r.stdout+r.stderr,encoding='utf-8',newline='\n');print(r.stdout+r.stderr,flush=True);r.check_returncode()
 result=read(destination/'result.json');assert result['passed'] and result['networkAttempts']==0;results.append(result)
for row in deps:assert sha(row['path'])==row['sha256Bytes']
for row in toolpins:assert sha(row['path'])==row['sha256Bytes']
assert sha(DLL)==inputs['dllSha256Bytes'] and sha(SOURCE)==inputs['sourceSha256Bytes'] and sha(APPROVAL)==inputs['approvalSha256Bytes']
assert sha(FIXTURE)==sha(frozen) and sha(SNAPSHOT)==inputs['snapshotSha256Bytes'] and sha(CP)==inputs['runtimeCpSha256Bytes']
save(OUT/'accepted-evidence.json',dict(passed=True,**inputs,fixtureJarSha256Bytes=sha(fixture_jar),
 productionOverrides=0,emittedClassOverlap=0,freshJvms=4,checks=sum(len(row['checks']) for row in results),results=results,
 MainIntegratedNativeSource=True,MainShellExecuted=False,HWND=False,ShareShow=False,ShareUI=False,
 DataRequested=False,SetStorageItems=False,receiver=False,packageAcceptance=False))
print('PASS 4 actual Main default-native-transport scenarios, zero product overrides',flush=True)
