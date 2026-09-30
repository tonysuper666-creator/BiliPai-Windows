from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,tempfile,zipfile,urllib.parse
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;BASE=HERE.parent/'dynamic-editor-main-product-snapshot-01'
def ext(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def save(p,v):ext(p).write_text(json.dumps(v,indent=2)+'\n',encoding='utf-8',newline='\n')
def args(p,values):ext(p).write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n',encoding='utf-8',newline='\n')
assert sha(BASE/'manifest.json')=='bc6cf9ef064ba68f28f36c3bbf2b59721629405567c7ced7e4799a844ff7b062'
assert sha(BASE/'ordered-runtime-cp.json')=='515bf598f56b27de45aa04d63493e1f8ad38b7e4710ef358fea97b9fc9d620c1'
rows=json.loads((BASE/'ordered-runtime-cp.json').read_bytes());assert len(rows)==89
for row in rows:assert sha(row['path'])==row['sha256Bytes']
spec=importlib.util.spec_from_file_location('follow_task_compiler',HERE.parent/'source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
name=sys.argv[1] if len(sys.argv)>1 else '01';out=HERE/'runs'/name;assert not out.exists();out.mkdir(parents=True)
sources=sorted((HERE/'candidate').rglob('*.kt'))
sourcepins=[dict(path=str(p.relative_to(HERE)),sha256Bytes=sha(p)) for p in sources+[HERE/'FollowObserverFixture.kt']]
for source in sources+[HERE/'run-proof.py',HERE/'FollowObserverFixture.kt']:
 dest=out/'frozen-sources'/source.relative_to(HERE);ext(dest.parent).mkdir(parents=True,exist_ok=True);ext(dest).write_bytes(source.read_bytes())
tools=[Path(c.JAVA),Path(c.__file__),Path(c.PLUGIN)]+list(map(Path,c.COMPILER))
toolpins=[dict(path=str(p),sha256Bytes=sha(p),bytes=ext(p).stat().st_size) for p in tools]
save(out/'compiler-tool-pins.json',toolpins)
cp=';'.join(row['path'] for row in rows);mainjar=BASE/'main-kotlin.jar';candidate=out/'candidate.jar'
def compile(label,output,sources,classpath,friend):
 args(out/(label+'.args'),['-no-stdlib','-no-reflect','-jvm-target','21','-cp',classpath,'-Xfriend-paths='+friend,
  '-Xplugin='+str(c.PLUGIN),'-module-name','com_bilipai_desktop_bilipai_windows' if label=='candidate' else 'follow_observer_task_fixture','-d',output]+sources)
 r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx3g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/(label+'.args'))],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=150)
 (out/(label+'.log')).write_text(r.stdout+r.stderr,encoding='utf-8');print(r.stdout+r.stderr);r.check_returncode()
compile('candidate',candidate,sources,cp,str(mainjar))
baseclasses={}
for row in rows:
 with zipfile.ZipFile(ext(row['path'])) as z:
  for n in z.namelist():
   if n.endswith('.class'):baseclasses[n]=row['path']
with zipfile.ZipFile(ext(candidate)) as z:
 overlaps=[n for n in z.namelist() if n in baseclasses]
 save(out/'declared-candidate-overrides.json',dict(preparedCandidateNotActualMain=True,overriddenClasses=overlaps,allClasses=[dict(entry=n,sha256Bytes=hashlib.sha256(z.read(n)).hexdigest()) for n in z.namelist()]))
fixture=out/'fixture.jar';compile('fixture',fixture,[HERE/'FollowObserverFixture.kt'],str(candidate)+';'+cp,str(candidate)+','+str(mainjar))
with zipfile.ZipFile(ext(fixture)) as z:
 classes=[n for n in z.namelist() if n.endswith('.class')];assert classes and not(set(classes)&(set(baseclasses)|set(overlaps)))
 assert all(n.startswith('com/bilipai/desktop/ui/followobserverproof/') for n in classes)
 save(out/'fixture-class-identities.json',[dict(entry=n,sha256Bytes=hashlib.sha256(z.read(n)).hexdigest()) for n in z.namelist()])
private=Path(tempfile.mkdtemp(prefix='bp-follow-'));env=os.environ.copy()
for key in ['APPDATA','LOCALAPPDATA','USERPROFILE','TEMP','TMP']:
 p=private/key.lower();p.mkdir();env[key]=str(p)
save(out/'task-private-environment.json',dict(root=str(private),noRealAccount=True))
save(out/'input-pins.json',dict(actualMainSnapshotSha256Bytes=sha(BASE/'manifest.json'),actualOrderedCpSha256Bytes=sha(BASE/'ordered-runtime-cp.json'),orderedDependencies=rows,candidateJarSha256Bytes=sha(candidate),candidateSources=[dict(path=str(p.relative_to(HERE)),sha256Bytes=sha(p)) for p in sources],fixtureSourceSha256Bytes=sha(HERE/'FollowObserverFixture.kt'),MainIntegrated=False,ShareUI=False,HTTP=False))
args(out/'run.args',['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.security.manager=allow','-Duser.home='+str(private),'-Djava.io.tmpdir='+str(private/'temp'),'-cp',str(fixture)+';'+str(candidate)+';'+cp,'com.bilipai.desktop.ui.followobserverproof.FollowObserverFixtureKt',out,private])
with (out/'run.log').open('wb') as log:
 p=subprocess.Popen([str(c.JAVA),'@'+str(out/'run.args')],stdout=log,stderr=subprocess.STDOUT,cwd=HERE,env=env)
 save(out/'own-process.json',dict(pid=p.pid))
 try:code=p.wait(timeout=50)
 except subprocess.TimeoutExpired:p.kill();p.wait();save(out/'timeout.json',dict(ownJvmKilled=True));code=-999
print((out/'run.log').read_text(encoding='utf-8',errors='replace'));assert code==0,code
result=json.loads((out/'result.json').read_bytes());assert result['passed']
classpins=[]
for entry in result['classSources']:
 name=entry['class'].replace('.','/')+'.class';resolved=None
 for path in [candidate]+[Path(row['path']) for row in rows]:
  with zipfile.ZipFile(ext(path)) as z:
   if name in z.namelist():resolved=(path,hashlib.sha256(z.read(name)).hexdigest());break
 assert resolved
 expectedPath,expectedSha=resolved
 observed=Path(urllib.parse.unquote(urllib.parse.urlsplit(entry['codeSource']).path).lstrip('/'))
 assert observed.resolve()==expectedPath.resolve(),entry
 assert entry['resourceSha256Bytes']==expectedSha,entry
 classpins.append(dict(className=entry['class'],expectedCodeSource=str(expectedPath),sha256Bytes=expectedSha,preparedOverride=expectedPath==candidate))
save(out/'verified-loaded-class-identities.json',dict(passed=True,checks=classpins))
for row in sourcepins:assert sha(HERE/row['path'])==row['sha256Bytes']
for row in rows:assert sha(row['path'])==row['sha256Bytes']
for row in toolpins:assert sha(row['path'])==row['sha256Bytes']
save(out/'accepted-evidence.json',dict(passed=True,actualBaselineSha256Bytes=sha(BASE/'manifest.json'),orderedCpSha256Bytes=sha(BASE/'ordered-runtime-cp.json'),candidateJarSha256Bytes=sha(candidate),fixtureJarSha256Bytes=sha(fixture),assertions=result['assertions'],caseCount=len(result['cases']),resultSha256Bytes=sha(out/'result.json'),loadedClassIdentitiesSha256Bytes=sha(out/'verified-loaded-class-identities.json'),preparedCandidateOverrides=len(overlaps),sourceBytesUnchangedDuringRun=True,productionIntegrated=False,realHTTP=False,account=False,HWND=False,package=False))
print('PASS prepared follow observer candidate with actual immutable Main dependencies')
