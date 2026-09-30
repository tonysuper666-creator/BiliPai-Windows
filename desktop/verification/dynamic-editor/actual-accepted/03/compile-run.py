from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,tempfile,zipfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;REPO=next(p for p in HERE.parents if (p/'.git').exists())
def safe(p):
 v=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(v if v.startswith(prefix) else prefix+v)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def args(p,v):write(p,'\n'.join('"'+str(x).replace('\\','/')+'"' for x in v)+'\n')
snapshot=REPO/'desktop/.local/dynamic-editor-main-product-snapshot-01/manifest.json'
assert sha(snapshot)=='bc6cf9ef064ba68f28f36c3bbf2b59721629405567c7ced7e4799a844ff7b062'
runtime=snapshot.with_name('ordered-runtime-cp.json');assert sha(runtime)=='515bf598f56b27de45aa04d63493e1f8ad38b7e4710ef358fea97b9fc9d620c1'
m=json.loads(safe(snapshot).read_bytes());ordered=json.loads(safe(runtime).read_bytes());assert len(ordered)==89
old=json.loads((REPO/'desktop/.local/dynamic-editor-detail-parity/final-production-safe/dependency-identities.json').read_text(encoding='utf-8'))
tests=[r for r in old if Path(r['path']).name.startswith('kotlin-test-') and 'junit' not in Path(r['path']).name];assert len(tests)==1
deps=ordered+tests
def verify():
 for row in deps:assert sha(row['path'])==row['sha256Bytes'],row['path']
 for row in m['sourceFiles']:assert hashlib.sha256(safe(REPO/row['path']).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==row['sha256Lf'],row['path']
 for row in m['generatedProductFiles']:assert sha(REPO/row['path'])==row['sha256Bytes'],row['path']
verify();write(HERE/'dependency-identities.json',json.dumps(deps,indent=2)+'\n')
spec=importlib.util.spec_from_file_location('editor_actual_compiler',REPO/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sources=sorted(HERE.glob('*Fixture.kt'));assert len(sources)==4
sourceRows=[dict(path=p.name,sha256Bytes=sha(p)) for p in sources]
write(HERE/'fixture-source-identities.json',json.dumps(sourceRows,indent=2)+'\n')
out=HERE/'classes';safe(out).mkdir(exist_ok=False);cp=';'.join(r['path'] for r in deps);friend=next(r['path'] for r in deps if Path(r['path']).name=='main-kotlin.jar')
file=HERE/'compiler.args';args(file,['-no-stdlib','-no-reflect','-jvm-target','21','-cp',cp,'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+friend,'-module-name','bilipai_dynamic_editor_actual_fixture','-d',str(out)]+list(map(str,sources)))
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(file)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=58)
write(HERE/'compile.log',r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
fixtureClasses={p.relative_to(out).as_posix() for p in out.rglob('*.class')};productClasses=set()
for row in ordered:
 with zipfile.ZipFile(safe(row['path'])) as z:productClasses.update(n for n in z.namelist() if n.endswith('.class'))
overlap=sorted(fixtureClasses&productClasses);assert not overlap,overlap
write(HERE/'class-overlap.json',json.dumps(dict(fixtureClassCount=len(fixtureClasses),productClassCount=len(productClasses),overlap=overlap,zeroProductOverrides=True),indent=2)+'\n')
proof=HERE/'runs/01';proof.mkdir(parents=True,exist_ok=False)
sandbox=Path(tempfile.mkdtemp(prefix='bpd-editor-actual-'));env=os.environ.copy()
for name in ['LOCALAPPDATA','APPDATA','USERPROFILE','HOME','TEMP','TMP']:
 p=sandbox/name.lower();p.mkdir();env[name]=str(p)
for name,main in [('ui','com.bilipai.desktop.ui.UiFixtureKt'),('policy','com.bilipai.desktop.ui.PolicyFixtureKt'),('protocol','com.bilipai.desktop.data.EditorTransportFixtureKt'),('root-ui','com.bilipai.desktop.ui.RootUiFixtureKt')]:
 if name!='root-ui':continue
 file=HERE/(name+'.args');args(file,['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.net.useSystemProxies=false','-Duser.home='+str(sandbox/'home'),'-Djava.io.tmpdir='+str(sandbox/'tmp'),'-cp',str(out)+';'+cp,main,str(proof)])
 r=subprocess.run([str(c.JAVA),'@'+str(file)],cwd=HERE,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=58)
 write(HERE/(name+'.log'),r.stdout+r.stderr);print(name+': '+r.stdout+r.stderr);r.check_returncode()
# Reuse only unchanged, already passed UI/policy/protocol fixture observations from attempt01.
prior=HERE.with_name('dynamic-editor-actual-main-proof-01')
for fixture in ['UiFixture.kt','PolicyFixture.kt','EditorTransportFixture.kt']:
 assert sha(HERE/fixture)==sha(prior/fixture)
for name in ['ui','policy','protocol']:
 log=safe(prior/(name+'.log')).read_bytes();assert b'PASS:' in log
 write(HERE/('accepted-previous-'+name+'.log'),log.decode('utf-8'))
for p in (prior/'runs/01').glob('*'):
 if p.name in ['root-ui-result.json','root-edit-sent.json'] or '-root-' in p.name:continue
 safe(proof/p.name).write_bytes(safe(p).read_bytes())
verify()
write(HERE/'run-evidence.json',json.dumps(dict(passed=True,actualMainSnapshotSha256Bytes=sha(snapshot),actualRuntimeCpSha256Bytes=sha(runtime),productClasspathEntries=89,fixtureSources=sourceRows,zeroProductOverrides=True,unchangedUiPolicyProtocolAcceptedFromAttempt01=True,rootUiRerunAfterNewObservation=True,results=[dict(path=p.relative_to(HERE).as_posix(),sha256Bytes=sha(p)) for p in sorted(proof.glob('*'))],HTTP=False,HWND=False,nativeChooser=False,persistentCredentials=False,fullFeatureParity=False,packaging=False),indent=2)+'\n')