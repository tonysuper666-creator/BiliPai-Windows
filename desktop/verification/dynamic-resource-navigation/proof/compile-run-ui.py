from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,tempfile,zipfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;REPO=next(p for p in HERE.parents if (p/'.git').exists())
def safe(p):
 v=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(v if v.startswith(prefix) else prefix+v)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def args(p,values):write(p,'\n'.join('"'+str(v).replace('\\','/')+'"'for v in values)+'\n')
snapshot=HERE/'main-product-snapshot-01/manifest.json';runtime=snapshot.with_name('ordered-runtime-cp.json')
m=json.loads(safe(snapshot).read_bytes());ordered=json.loads(safe(runtime).read_bytes())
assert len(ordered)==89 and m['mainCompile']['focusedTests']==8
assert sha(runtime)==m['actualRuntimeClasspath']['orderedIdentitiesSha256Bytes']
old=json.loads((REPO/'desktop/.local/dynamic-editor-detail-parity/final-production-safe/dependency-identities.json').read_text(encoding='utf-8'))
tests=[r for r in old if Path(r['path']).name.startswith('kotlin-test-') and 'junit' not in Path(r['path']).name];assert len(tests)==1
deps=ordered+tests
def verify():
 for row in deps:assert sha(row['path'])==row['sha256Bytes'],row['path']
 for row in m['sourceFiles']:assert hashlib.sha256(safe(REPO/row['path']).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==row['sha256Lf'],row['path']
 for row in m['generatedProductFiles']:assert sha(REPO/row['path'])==row['sha256Bytes'],row['path']
verify();write(HERE/'ui-dependency-identities.json',json.dumps(deps,indent=2)+'\n')
spec=importlib.util.spec_from_file_location('dynamic_route_compiler',REPO/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
source=HERE/'FolderUiFixture.kt';sourceHash=sha(source);out=HERE/'ui-classes-01';safe(out).mkdir(exist_ok=False)
cp=';'.join(r['path'] for r in deps);friend=next(r['path']for r in deps if Path(r['path']).name=='main-kotlin.jar')
file=HERE/'ui-compiler-01.args';args(file,['-no-stdlib','-no-reflect','-jvm-target','21','-cp',cp,'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+friend,'-module-name','bilipai_dynamic_resource_navigation_ui_fixture','-d',str(out),str(source)])
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(file)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=58)
write(HERE/'ui-compile-01.log',r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
fixtureClasses={p.relative_to(out).as_posix()for p in out.rglob('*.class')};productClasses=set()
for row in ordered:
 with zipfile.ZipFile(safe(row['path']))as z:productClasses.update(n for n in z.namelist()if n.endswith('.class'))
overlap=sorted(fixtureClasses&productClasses);assert not overlap,overlap
write(HERE/'ui-class-overlap-01.json',json.dumps(dict(fixtureClasses=len(fixtureClasses),productClasses=len(productClasses),overlap=overlap,zeroProductOverrides=True),indent=2)+'\n')
proof=HERE/'ui-runs/01';safe(proof).mkdir(parents=True,exist_ok=False)
sandbox=Path(tempfile.mkdtemp(prefix='bpd-dynamic-route-'));env=os.environ.copy()
for name in ['LOCALAPPDATA','APPDATA','USERPROFILE','HOME','TEMP','TMP']:
 p=sandbox/name.lower();p.mkdir();env[name]=str(p)
file=HERE/'ui-runtime-01.args';args(file,['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.net.useSystemProxies=false','-Duser.home='+str(sandbox/'home'),'-Djava.io.tmpdir='+str(sandbox/'tmp'),'-cp',str(out)+';'+cp,'com.bilipai.desktop.ui.FolderUiFixtureKt',str(proof)])
r=subprocess.run([str(c.JAVA),'@'+str(file)],cwd=HERE,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=58)
write(HERE/'ui-runtime-01.log',r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode();verify();assert sha(source)==sourceHash
write(HERE/'ui-evidence-01.json',json.dumps(dict(passed=True,snapshotSha256Bytes=sha(snapshot),orderedRuntimeCpSha256Bytes=sha(runtime),actualProductClasspathEntries=89,fixtureSourceSha256Bytes=sourceHash,zeroProductOverrides=True,results=[dict(path=p.relative_to(HERE).as_posix(),sha256Bytes=sha(p))for p in sorted(proof.glob('*'))],realNetwork=False,nativeWindow=False,MainShellExecuted=False,packaged=False,fullFeatureParity=False),indent=2)+'\n')
