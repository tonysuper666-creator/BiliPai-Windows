"""Pure fixture source against exact current product3 JARs; no source overrides/shared Gradle/windows."""
from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,tempfile,zipfile
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,v):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(v,encoding='utf-8',newline='\n')
snapshot=HERE.parent/'blocked-up-main-product-snapshot/manifest.json'
assert sha(snapshot)=='10e08fbc781a62ceff68c06c768677922925679b6121f59f7eb43ab56845cd7b'
manifest=json.loads(snapshot.read_text(encoding='utf-8'))
deps=manifest['artifacts']+json.loads((HERE.parent/'network-proxy-product-proof/dependency-identities.json').read_text(encoding='utf-8'))[3:]
def verify():
 assert sha(snapshot)=='10e08fbc781a62ceff68c06c768677922925679b6121f59f7eb43ab56845cd7b'
 for r in deps:assert sha(Path(r['path']))==r['sha256Bytes'],r['path']
verify();write(HERE/'dependency-identities.json',json.dumps(deps,indent=2)+'\n')
spec=importlib.util.spec_from_file_location('product_ui_compiler',HERE.parent/'source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
out=HERE/'classes-attempt2';safe(out).mkdir(parents=True,exist_ok=False);cp=[r['path'] for r in deps]
def args(p,values):write(p,'\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n')
argfile=HERE/'compiler.args';args(argfile,['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xplugin='+str(c.PLUGIN),
 '-Xfriend-paths='+cp[0],'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(out),str(HERE/'ProductStorageUiFixture.kt')])
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],cwd=REPO,
 capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
write(HERE/'compile.log',r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
fixture_names={str(p.relative_to(safe(out))).replace('\\','/') for p in safe(out).rglob('*.class')}
with zipfile.ZipFile(Path(cp[0])) as z:assert not fixture_names.intersection(z.namelist()),'Fixture accidentally shadows a real product class'
sandbox=Path(tempfile.mkdtemp(prefix='bpd-product-ui-'));env=os.environ.copy()
for name in ['LOCALAPPDATA','APPDATA','USERPROFILE','HOME','TEMP','TMP']:
 p=sandbox/name.lower();p.mkdir();env[name]=str(p)
argfile=HERE/'runtime.args';args(argfile,['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.net.useSystemProxies=false',
 '-Duser.home='+str(sandbox/'home'),'-Djava.io.tmpdir='+str(sandbox/'tmp'),'-cp',str(out)+';'+';'.join(cp),
 'com.bilipai.desktop.ui.ProductStorageUiFixtureKt',str(HERE/'proof')])
r=subprocess.run([str(c.JAVA),'@'+str(argfile)],cwd=HERE,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
write(HERE/'runtime.log',r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode();verify()
write(HERE/'compile-evidence.json',json.dumps(dict(passed=True,activeClassDirectory=out.name,source='ProductStorageUiFixture.kt',sourceSha256Bytes=sha(HERE/'ProductStorageUiFixture.kt'),
 snapshotManifest=str(snapshot),snapshotManifestSha256Bytes=sha(snapshot),productSnapshotJars=deps[:3],dependencyCount=len(deps),
 fixtureDoesNotShadowAnyProductClass=True,productionSourceOverrides=0,GuardBoundaryTreeRepositoryLoadedFromActualSnapshot=True,
 sandbox=str(sandbox),sharedGradle=False,HWND=False,realAccountData=False,HTTP=False,actualArchiveRestoreClaimed=False),indent=2)+'\n')
