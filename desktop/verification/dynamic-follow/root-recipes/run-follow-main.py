"""Frozen original follow fixture against the new actual Main; no candidate classes."""
from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,tempfile,zipfile,urllib.parse
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;REPO=next(p for p in HERE.parents if(p/'.git').exists())
LANE=REPO/'desktop/.local/dynamic-follow-observer-parity';BASE=HERE/'main-product-snapshot-01';OUT=HERE/'follow-proof01'
assert not OUT.exists();OUT.mkdir()
def ext(p):
 value=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
 return Path(value if value.startswith(prefix)else prefix+value)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def read(p):return json.loads(ext(p).read_bytes())
def save(p,v):ext(p).write_text(json.dumps(v,indent=2)+'\n',encoding='utf-8',newline='\n')
def args(p,values):ext(p).write_text('\n'.join('"'+str(v).replace('\\','/')+'"'for v in values)+'\n',encoding='utf-8',newline='\n')
assert sha(BASE/'manifest.json')=='d3fd2de6caf3302a65547ef29350ba1a158dead69d84c9b680131db88f7f62cd'
assert sha(BASE/'ordered-runtime-cp.json')=='2dc490f5604c2029bbee4f83fb1e20ee3ad83607b18c5a6a79505d90f941e629'
assert sha(LANE/'frozen-handoff-v2.json')=='fe16610024638f562846e409c549f72542cfb79abed50bf678a2998dbf3d0635'
handoff=read(LANE/'frozen-handoff-v2.json')
fixtureEntry=next(r for r in handoff['files']if r['path']=='runs/08/frozen-sources/FollowObserverFixture.kt')
source=LANE/fixtureEntry['path'];assert sha(source)==fixtureEntry['sha256Bytes']
frozen=OUT/'FollowObserverFixture.kt';ext(frozen).write_bytes(ext(source).read_bytes())
snapshot=read(BASE/'manifest.json');rows=read(BASE/'ordered-runtime-cp.json');assert len(rows)==89
def verify_main():
 for row in rows:assert sha(row['path'])==row['sha256Bytes']
 for row in snapshot['sourceFiles']:assert hashlib.sha256(ext(REPO/row['path']).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==row['sha256Lf'],row['path']
 for row in snapshot['generatedProductFiles']:assert sha(REPO/row['path'])==row['sha256Bytes'],row['path']
verify_main()
spec=importlib.util.spec_from_file_location('follow_actual_main_compiler',HERE.parent/'source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
toolpins=[dict(path=str(p),sha256Bytes=sha(p),bytes=ext(p).stat().st_size)for p in [Path(c.JAVA),Path(c.__file__),Path(c.PLUGIN),*map(Path,c.COMPILER)]]
save(OUT/'compiler-tool-pins.json',toolpins)
cp=';'.join(r['path']for r in rows);mainjar=BASE/'main-kotlin.jar';fixture=OUT/'fixture-only.jar'
values=['-Dfile.encoding=UTF-8','-Xmx3g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
 '-no-stdlib','-no-reflect','-jvm-target','21','-cp',cp,'-Xfriend-paths='+str(mainjar),'-Xplugin='+str(c.PLUGIN),
 '-module-name','follow_observer_actual_main_fixture','-d',str(fixture),str(frozen)]
args(OUT/'compile.args',values)
r=subprocess.run([str(c.JAVA),'@'+str(OUT/'compile.args')],capture_output=True,encoding='utf-8',errors='replace',timeout=150)
ext(OUT/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8',newline='\n');print(r.stdout+r.stderr,flush=True);r.check_returncode()
baseclasses=set()
for row in rows:
 with zipfile.ZipFile(ext(row['path']))as z:baseclasses.update(z.namelist())
with zipfile.ZipFile(ext(fixture))as z:
 classes=[n for n in z.namelist()if n.endswith('.class')]
 assert classes and not(set(classes)&baseclasses)
 assert all(n.startswith('com/bilipai/desktop/ui/followobserverproof/')for n in classes)
 save(OUT/'fixture-class-identities.json',[dict(entry=n,sha256Bytes=hashlib.sha256(z.read(n)).hexdigest())for n in z.namelist()])
private=Path(tempfile.mkdtemp(prefix='bp-follow-main-'));env=os.environ.copy()
for key in ['APPDATA','LOCALAPPDATA','USERPROFILE','TEMP','TMP']:
 p=private/key.lower();p.mkdir();env[key]=str(p)
env.pop('JAVA_TOOL_OPTIONS',None)
save(OUT/'task-private-environment.json',dict(root=str(private),noRealAccount=True))
save(OUT/'input-pins.json',dict(actualMainSnapshotSha256Bytes=sha(BASE/'manifest.json'),actualOrderedCpSha256Bytes=sha(BASE/'ordered-runtime-cp.json'),
 orderedDependencies=rows,fixtureSourceSha256Bytes=sha(frozen),MainIntegrated=True,productionOverrides=0,sourcePins=383,generatedPins=703,
 RootEpochEffectCompiled=True,RootShellExecuted=False,ShareUI=False,externalHTTP=False))
args(OUT/'run.args',['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.security.manager=allow','-Duser.home='+str(private),
 '-Djava.io.tmpdir='+str(private/'temp'),'-cp',str(fixture)+';'+cp,'com.bilipai.desktop.ui.followobserverproof.FollowObserverFixtureKt',str(OUT),str(private)])
with ext(OUT/'run.log').open('wb')as log:
 p=subprocess.Popen([str(c.JAVA),'@'+str(OUT/'run.args')],stdout=log,stderr=subprocess.STDOUT,cwd=HERE,env=env)
 save(OUT/'own-process.json',dict(pid=p.pid))
 try:code=p.wait(timeout=50)
 except subprocess.TimeoutExpired:p.kill();p.wait();save(OUT/'timeout.json',dict(ownJvmKilled=True));code=-999
print(ext(OUT/'run.log').read_text(encoding='utf-8',errors='replace'),flush=True);assert code==0,code
result=read(OUT/'result.json');assert result['passed']and result['assertions']==92 and len(result['cases'])==18
classpins=[]
for entry in result['classSources']:
 name=entry['class'].replace('.','/')+'.class';resolved=None
 for row in rows:
  with zipfile.ZipFile(ext(row['path']))as z:
   if name in z.namelist():resolved=(Path(row['path']),hashlib.sha256(z.read(name)).hexdigest());break
 assert resolved
 expectedPath,expectedSha=resolved;observed=Path(urllib.parse.unquote(urllib.parse.urlsplit(entry['codeSource']).path).lstrip('/'))
 assert observed.resolve()==expectedPath.resolve()and entry['resourceSha256Bytes']==expectedSha,entry
 classpins.append(dict(className=entry['class'],expectedCodeSource=str(expectedPath),sha256Bytes=expectedSha,preparedOverride=False))
save(OUT/'verified-loaded-class-identities.json',dict(passed=True,checks=classpins))
verify_main()
for row in toolpins:assert sha(row['path'])==row['sha256Bytes']
assert sha(frozen)==fixtureEntry['sha256Bytes']
save(OUT/'accepted-evidence.json',dict(passed=True,actualMainSnapshotSha256Bytes=sha(BASE/'manifest.json'),orderedCpSha256Bytes=sha(BASE/'ordered-runtime-cp.json'),
 fixtureSourceSha256Bytes=sha(frozen),fixtureJarSha256Bytes=sha(fixture),assertions=result['assertions'],caseCount=len(result['cases']),
 resultSha256Bytes=sha(OUT/'result.json'),loadedClassIdentitiesSha256Bytes=sha(OUT/'verified-loaded-class-identities.json'),
 productionOverrides=0,emittedClassOverlap=0,MainIntegrated=True,sourceAndGeneratedBytesUnchangedDuringRun=True,
 RootEpochEffectCompiled=True,RootShellExecuted=False,actualRetrofitTerminalTransport=True,externalHTTP=False,account=False,HWND=False,package=False))
print('PASS actual integrated Main follow observer 92 assertions/18 scenarios with zero product overrides',flush=True)
