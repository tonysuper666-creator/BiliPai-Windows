"""Independent thin Boundary delta; never mutate the original frozen162 or product."""
from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,tempfile,difflib
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2];OLD=HERE.parent/'discovery-storage-error-parity'
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,v):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(v,encoding='utf-8',newline='\n')
def verify_old():
 assert sha(OLD/'verified-artifacts.json')=='422f8f9a91cedfcd61a021f227566d73509e6daf8921b175fbfa91c9123588c4'
 for r in json.loads((OLD/'verified-artifacts.json').read_text())['files']:assert sha(OLD/r['path'])==r['sha256Bytes'],r['path']
verify_old()
deps=json.loads((OLD/'dependency-identities.json').read_text())
def verify_deps():
 for r in deps:assert sha(Path(r['path']))==r['sha256Bytes'],r['path']
verify_deps();write(HERE/'dependency-identities.json',json.dumps(deps,indent=2)+'\n')
sources=[]
for original in (OLD/'marked-overrides').rglob('*.kt'):
 destination=HERE/'marked-overrides'/original.relative_to(OLD/'marked-overrides')
 safe(destination.parent).mkdir(parents=True,exist_ok=True);safe(destination).write_bytes(safe(original).read_bytes());assert sha(destination)==sha(original);sources.append(destination)
for name in ['DesktopDiscoveryStorageGuard.kt','DiscoveryStorageFixture.kt','DiscoveryStorageUiFixture.kt']:
 dest=HERE/name
 if name=='DesktopDiscoveryStorageGuard.kt':
  base=safe(OLD/name).read_text(encoding='utf-8')
  original='override fun close()=synchronized(lock){closed=true;generation++;pending=null}'
  assert base.count(original)==1
  write(dest,base.replace(original,'override fun close()=synchronized(lock){closed=true;generation++;pending=null;mutableResult.value=null}'))
 else:
  safe(dest).write_bytes(safe(OLD/name).read_bytes());assert sha(dest)==sha(OLD/name)
 sources.append(dest)
sources.extend([HERE/'DesktopDiscoveryStorageBoundary.kt',HERE/'BoundaryMountedUiFixture.kt',HERE/'SameMidEpochFixture.kt'])
spec=importlib.util.spec_from_file_location('review_compiler',HERE.parent/'source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
serial=c.jar('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.4.0')
cp=[r['path'] for r in deps];out=HERE/'classes-attempt4';safe(out).mkdir(parents=True,exist_ok=False)
def args(path,values):write(path,'\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n')
argfile=HERE/'compiler.args';args(argfile,['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xplugin='+str(c.PLUGIN),'-Xplugin='+str(serial),
 '-Xfriend-paths='+cp[0],'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(out),*map(str,sources)])
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],cwd=REPO,
 capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
write(HERE/'compile.log',r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
sandbox=Path(tempfile.mkdtemp(prefix='bpd-mounted-'));env=os.environ.copy()
for name in ['LOCALAPPDATA','APPDATA','USERPROFILE','HOME','TEMP','TMP']:
 p=sandbox/name.lower();p.mkdir();env[name]=str(p)
for label,main,output in [('mounted-pointer','BoundaryMountedUiFixtureKt',HERE/'proof/mounted-pointer'),('actual-same-mid','SameMidEpochFixtureKt',HERE/'proof/actual-same-mid.json')]:
 argfile=HERE/(label+'-runtime.args');args(argfile,['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.net.useSystemProxies=false',
  '-Duser.home='+str(sandbox/'home'),'-Djava.io.tmpdir='+str(sandbox/'tmp'),'-cp',str(out)+';'+';'.join(cp),'com.bilipai.desktop.ui.'+main,str(output)])
 r=subprocess.run([str(c.JAVA),'@'+str(argfile)],cwd=HERE,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
 write(HERE/(label+'-runtime.log'),r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
verify_old();verify_deps()
unchanged=[]
for p in safe(out/'com/bilipai/desktop/ui').glob('*.class'):
 if p.name.startswith(('DesktopDiscoveryStorage','DiscoveryStorageFixture','DiscoveryStorageUiFixture')):
  old=HERE/'classes-attempt3/com/bilipai/desktop/ui'/p.name
  assert sha(old)==sha(p),p.name
  unchanged.append(dict(name=p.name,sha256Bytes=sha(p)))
write(HERE/'same-production-classes-regression.json',json.dumps(dict(passed=True,oldDiskAndPointerActiveDirectory='classes-attempt3',
  finalMountedPointerActiveDirectory=out.name,unchangedClassCount=len(unchanged),unchangedClasses=unchanged),indent=2)+'\n')
patches=[];payload=[]
for name in ['DesktopDiscoveryStorageGuard.kt','DesktopDiscoveryStorageBoundary.kt']:
 relative='desktop/src/main/kotlin/com/bilipai/desktop/ui/'+name
 base=(OLD/name).read_text(encoding='utf-8');desired=(HERE/name).read_text(encoding='utf-8')
 dest=HERE/'prepared'/relative;write(dest,desired)
 patches.append(''.join(difflib.unified_diff(base.splitlines(True),desired.splitlines(True),fromfile='a/'+relative,tofile='b/'+relative)))
 payload.append(dict(path=relative,baselineSha256Bytes=sha(OLD/name),desiredSha256Bytes=sha(dest)))
write(HERE/'integration.patch',''.join(patches));write(HERE/'production-payload.json',json.dumps(payload,indent=2)+'\n')
write(HERE/'compile-evidence.json',json.dumps(dict(passed=True,activeClassDirectory=out.name,sources=[dict(path=str(p.relative_to(HERE)),sha256Bytes=sha(p)) for p in sources],
 productionPayload=payload,
 oldFrozenManifestSha256Bytes=sha(OLD/'verified-artifacts.json'),sandbox=str(sandbox),rootRestoreFenceClaimed=False,sharedGradle=False,HWND=False,HTTP=False,userAccountFiles=False),indent=2)+'\n')
