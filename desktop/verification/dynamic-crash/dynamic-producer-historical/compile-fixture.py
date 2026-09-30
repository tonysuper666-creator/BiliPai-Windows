from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,tempfile,zipfile
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
ATTEMPT=sys.argv[1] if len(sys.argv)>1 else '1'
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,v):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(v,encoding='utf-8',newline='\n')
def args(p,values):write(p,'\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n')
prep=subprocess.run([sys.executable,str(HERE/'prepare.py')],capture_output=True,text=True,encoding='utf-8',errors='replace');print(prep.stdout+prep.stderr);prep.check_returncode()
old=json.loads((HERE.parent/'discovery-storage-final-product-ui-proof/dependency-identities.json').read_text(encoding='utf-8'))
snapshot=HERE.parent/'diagnostics-main-product-snapshot/manifest.json'
pin=sha(snapshot)
deps=[dict(path=r['path'],sha256Bytes=r['sha256Bytes']) for r in json.loads(snapshot.read_text(encoding='utf-8'))['artifacts']]+old[3:]
def verify():
 for row in deps:assert sha(Path(row['path']))==row['sha256Bytes'],row['path']
 for row in json.loads((HERE/'target-baselines.json').read_text(encoding='utf-8')):assert sha(REPO/row['path'])==row['baseSha256Bytes'],row['path']
verify();write(HERE/'dependency-identities.json',json.dumps(deps,indent=2)+'\n')
spec=importlib.util.spec_from_file_location('dynamic_compiler',HERE.parent/'source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sources=list((HERE/'generated').rglob('*.kt'))+list((HERE/'prepared/desktop/src').rglob('*.kt'))+[HERE/'JunitFixture.kt']
if (HERE/'UiFixture.kt').exists():sources+=[HERE/'UiFixture.kt']
if (HERE/'TransportFixture.kt').exists():sources+=[HERE/'TransportFixture.kt']
cp=[row['path'] for row in deps];out=HERE/('classes-attempt'+ATTEMPT);safe(out).mkdir(exist_ok=False)
file=HERE/('compiler-'+ATTEMPT+'.args');args(file,['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+cp[0],
 '-module-name','com_bilipai_desktop_bilipai_windows','-d',str(out)]+[str(p) for p in sources])
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(file)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=50)
write(HERE/('compile-'+ATTEMPT+'.log'),r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
safe(HERE/'proof').mkdir(exist_ok=True);sandbox=Path(tempfile.mkdtemp(prefix='bpd-dynamic-'));env=os.environ.copy()
for name in ['LOCALAPPDATA','APPDATA','USERPROFILE','HOME','TEMP','TMP']:
 p=sandbox/name.lower();p.mkdir();env[name]=str(p)
mains=[('junit','com.bilipai.desktop.ui.JunitFixtureKt',[str(HERE/'proof/junit-result.json')])]
if (HERE/'UiFixture.kt').exists():mains+=[('ui','com.bilipai.desktop.ui.UiFixtureKt',[str(HERE/'proof')])]
if (HERE/'TransportFixture.kt').exists():mains+=[('transport','com.bilipai.desktop.ui.TransportFixtureKt',[str(HERE/'proof/transport-result.json')])]
for name,main,tail in mains:
 file=HERE/(name+'-'+ATTEMPT+'.args');args(file,['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.net.useSystemProxies=false','-Duser.home='+str(sandbox/'home'),
  '-Djava.io.tmpdir='+str(sandbox/'tmp'),'-cp',str(out)+';'+';'.join(cp),main]+tail)
 r=subprocess.run([str(c.JAVA),'@'+str(file)],cwd=HERE,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
 write(HERE/(name+'-'+ATTEMPT+'.log'),r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
verify();write(HERE/'compile-evidence.json',json.dumps(dict(passed=True,activeClasses=out.name,sources=[dict(path=str(p.relative_to(HERE)).replace('\\','/'),sha256Bytes=sha(p)) for p in sources],
 currentProductJars=deps[:3],productSnapshotManifestSha256Bytes=pin,isolatedConsumerOverrides=2,mainFilesChanged=False,sharedGradle=False,HWND=False,HTTP=False),indent=2)+'\n')
