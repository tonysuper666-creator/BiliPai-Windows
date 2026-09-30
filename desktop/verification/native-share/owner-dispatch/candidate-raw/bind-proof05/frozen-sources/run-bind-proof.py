from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,tempfile,zipfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;BASE=HERE.parent/'native-share-main-product-snapshot-01'
def ext(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def save(p,v):ext(p).write_text(json.dumps(v,indent=2)+'\n',encoding='utf-8',newline='\n')
def args(p,values):ext(p).write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n',encoding='utf-8',newline='\n')
assert sha(BASE/'manifest.json')=='4e97c4c054a7ec0ed8f064319009bcfb9b68f9c191ef089a6bf677f163df959f'
assert sha(BASE/'ordered-runtime-cp.json')=='a733a3435e18c5dac83ff155023c36e6d0d1997822b0a6b77ba7d4efeff63fcc'
raw=json.loads((BASE/'ordered-runtime-cp.json').read_bytes());rows=raw if isinstance(raw,list) else raw['entries'];assert len(rows)==89
for row in rows:assert sha(row['path'])==row['sha256Bytes']
source=HERE/'OwnerBindFixture.kt';dll=HERE/'build04/bilipai-diagnostic-share.dll';graph=HERE/'build04/producer-input-graph.json'
native=json.loads(graph.read_bytes());assert sha(dll)==native['dll']['sha256Bytes']
for key in ['transitiveHeaders','searchedLibrariesConservativePins','compilerBinDirectoryConservativePins']:
 for row in native[key]:assert sha(row['path'])==row['sha256Bytes']
spec=importlib.util.spec_from_file_location('owner_fixture_compiler',HERE.parent/'source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
out=HERE/'bind-proof05';assert not out.exists();out.mkdir();frozen=out/'frozen-sources';frozen.mkdir()
for file in [source,HERE/'run-bind-proof.py']:(frozen/file.name).write_bytes(file.read_bytes())
all_entries=set()
for row in rows:
 with zipfile.ZipFile(ext(row['path'])) as jar:all_entries.update(jar.namelist())
fixture=out/'fixture-only.jar';cp=';'.join(r['path'] for r in rows)
args(out/'compile.args',['-no-stdlib','-no-reflect','-jvm-target','21','-cp',cp,'-module-name','owner_bind_native_task_proof','-d',fixture,source])
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compile.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
(out/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8');print(r.stdout+r.stderr);r.check_returncode()
with zipfile.ZipFile(ext(fixture)) as jar:
 classes=[n for n in jar.namelist() if n.endswith('.class')];assert classes and not(set(classes)&all_entries)
 assert all(n.startswith('com/bilipai/desktop/diagnostics/ownerbindproof/') for n in classes)
 save(out/'fixture-class-identities.json',[{'entry':n,'sha256Bytes':hashlib.sha256(jar.read(n)).hexdigest()} for n in jar.namelist()])
save(out/'input-pins.json',{'actualMainSnapshotSha256Bytes':sha(BASE/'manifest.json'),'actualOrderedCpSha256Bytes':sha(BASE/'ordered-runtime-cp.json'),'orderedDependencies':rows,'taskFixtureSourceSha256Bytes':sha(source),'taskDllSha256Bytes':sha(dll),'taskNativeSourceSha256Bytes':sha(HERE/'candidate04/DesktopDiagnosticShare.cpp'),'freshNativeGraphSha256Bytes':sha(graph),'productionOverrides':0,'MainExecuted':False,'sourceControlledApprovalChanged':False})
sandbox=Path(tempfile.mkdtemp(prefix='bp-bind-'));env=os.environ.copy()
for key in ['APPDATA','LOCALAPPDATA','USERPROFILE','TEMP','TMP']:
 d=sandbox/key.lower();d.mkdir();env[key]=str(d)
proof=out/'proof';proof.mkdir();save(out/'task-owned-environment.json',{'path':str(sandbox),'shortSkikoHome':True})
args(out/'run.args',['-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-Djava.awt.headless=false','-Dskiko.renderApi=SOFTWARE','-Djava.security.manager=allow','-Duser.home='+str(sandbox),'-Djava.io.tmpdir='+str(sandbox/'temp'),'-cp',';'.join([str(fixture),cp]),'com.bilipai.desktop.diagnostics.ownerbindproof.OwnerBindFixtureKt',proof,dll,sha(dll)])
with (out/'run.log').open('wb') as log:
 process=subprocess.Popen([str(c.JAVA),'@'+str(out/'run.args')],stdout=log,stderr=subprocess.STDOUT,env=env,cwd=HERE)
 save(out/'own-process.json',{'pid':process.pid})
 try:code=process.wait(timeout=30)
 except subprocess.TimeoutExpired:
  stack=subprocess.run([str(c.JAVA.with_name('jstack.exe')),str(process.pid)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=15)
  (out/'own-jvm-stack.log').write_text(stack.stdout+stack.stderr,encoding='utf-8')
  try:code=process.wait(timeout=15)
  except subprocess.TimeoutExpired:process.kill();process.wait();save(out/'timeout.json',{'ownJvmKilled':True,'ShareShow':False});code=-999
print((out/'run.log').read_text(encoding='utf-8',errors='replace'));assert code==0,code
result=json.loads((proof/'result.json').read_bytes());assert result['passed']
for row in rows:assert sha(row['path'])==row['sha256Bytes']
save(out/'accepted-evidence.json',{'passed':True,'actualMainSnapshotSha256Bytes':sha(BASE/'manifest.json'),'runtimeCpSha256Bytes':sha(BASE/'ordered-runtime-cp.json'),'taskFixtureJarSha256Bytes':sha(fixture),'taskFixtureSourceSha256Bytes':sha(source),'taskDllSha256Bytes':sha(dll),'nativeGraphSha256Bytes':sha(graph),'resultSha256Bytes':sha(proof/'result.json'),'assertions':result['assertions'],'actualHiddenHwndNativeBindAndRetire':True,'MainIntegrated':False,'ShareShow':False,'ShareUI':False,'receiver':False,'productionOverrides':0})
print('PASS actual own-HWND owner-thread WinRT Bind/Retire, no ShareShow or receiver')
